// WebGL2 vehicle layer for the 3D live map (DR-111, ADR-0033): a faithful MapLibre 6 custom layer, ported from the
// S-07 spike. It shares MapLibre's framebuffer and enables the depth test, so extruded buildings occlude the
// vehicles. Two instanced draws cover buses and trains; a third draws billboard route discs. The motion model is
// the single source of positions, so the layer never interpolates on its own.

import type { CustomLayerInterface, CustomRenderMethodInput, Map as MapLibreMap } from 'maplibre-gl';

import { LabelAtlas } from './atlas';
import {
  metresPerPixel,
  mercatorUnitsPerMetre,
  mercatorXY,
  pickNearest,
  projectToScreen,
  type PickPoint,
} from './geometry';
import { busMesh, trainMesh } from './mesh';
import type { DrawnVehicle, MotionModel } from './motion';

// One instance: mercator x/y, heading (rad), colour rgb, alpha, scale, flags (bit 0 = selected).
const VEHICLE_STRIDE = 9;
// One label instance: mercator xyz, colour rgb, alpha, atlas cell, disc scale.
const LABEL_STRIDE = 9;
const BUS_LENGTH_M = 12;
const TRAIN_LENGTH_M = 33;
const MIN_LENGTH_PX = 14;
const LABEL_ZOOM = 14;
const LABEL_HALF_PX = 11;
const LABEL_ALTITUDE_M = 8;
const PICK_RADIUS_PX = 18;

const LABEL_QUAD = new Float32Array([-1, -1, 1, -1, 1, 1, -1, 1]);
const LABEL_QUAD_INDICES = new Uint16Array([0, 1, 2, 0, 2, 3]);

const VEHICLE_VS = `#version 300 es
in vec3 a_local;
in float a_role;
in vec2 a_offset;
in float a_heading;
in vec3 a_color;
in float a_alpha;
in float a_scale;
in float a_flags;
uniform mat4 u_matrix;
uniform float u_meter;
out vec3 v_local;
out float v_role;
out vec3 v_color;
out float v_alpha;
void main() {
  vec3 p = a_local * a_scale;
  float c = cos(a_heading), s = sin(a_heading);
  // The mesh points to +Y (north); rotate it in ENU, then flip y for mercator (y grows south).
  vec3 r = vec3(p.x * c + p.y * s, p.y * c - p.x * s, p.z);
  vec2 merc = a_offset + vec2(r.x, -r.y) * u_meter;
  gl_Position = u_matrix * vec4(merc.x, merc.y, r.z * u_meter, 1.0);
  bool selected = mod(a_flags, 2.0) >= 0.5;
  v_color = selected ? mix(a_color, vec3(1.0), 0.35) : a_color;
  v_local = p;
  v_role = a_role;
  v_alpha = a_alpha;
}`;

const VEHICLE_FS = `#version 300 es
precision mediump float;
in vec3 v_local;
in float v_role;
in vec3 v_color;
in float v_alpha;
out vec4 outColor;
void main() {
  int role = int(v_role + 0.5);
  vec3 base;
  if (role == 1) base = vec3(0.10, 0.13, 0.18);       // glass
  else if (role == 2) base = vec3(0.12, 0.12, 0.14);  // wheel / pantograph
  else if (role == 3) base = vec3(1.0, 0.97, 0.85);   // headlight
  else if (role == 4) base = vec3(0.85, 0.10, 0.08);  // tail light
  else if (role == 5) base = vec3(0.30, 0.32, 0.36);  // door
  else if (role == 6) base = vec3(0.20, 0.20, 0.22);  // connector
  else base = v_color;                                 // body: the instance colour
  // A cheap per-face shade from the object-space normal: tops bright, sides darker.
  vec3 n = normalize(cross(dFdx(v_local), dFdy(v_local)));
  outColor = vec4(base * (0.6 + 0.4 * abs(n.z)), v_alpha);
}`;

const LABEL_VS = `#version 300 es
in vec2 a_corner;
in vec3 a_offset;
in vec3 a_color;
in float a_alpha;
in float a_cell;
in float a_label_scale;
uniform mat4 u_matrix;
uniform vec2 u_viewport;
uniform vec2 u_cells;
uniform float u_half_px;
out vec2 v_uv;
out vec3 v_color;
out float v_alpha;
void main() {
  vec4 clip = u_matrix * vec4(a_offset, 1.0);
  // A billboard: shift the corner by a fixed pixel size after projection, so the disc stays ~22 px on screen.
  // Flipping y here (with the atlas uploaded unflipped) keeps the text upright.
  vec2 px = vec2(a_corner.x, -a_corner.y) * (u_half_px * a_label_scale) * 2.0 / u_viewport;
  clip.xy += px * clip.w;
  gl_Position = clip;
  float col = mod(a_cell, u_cells.x);
  float row = floor(a_cell / u_cells.x);
  v_uv = (vec2(col, row) + a_corner * 0.5 + 0.5) / u_cells;
  v_color = a_color;
  v_alpha = a_alpha;
}`;

const LABEL_FS = `#version 300 es
precision mediump float;
uniform sampler2D u_atlas;
in vec2 v_uv;
in vec3 v_color;
in float v_alpha;
out vec4 outColor;
void main() {
  vec4 texel = texture(u_atlas, v_uv);
  if (texel.a < 0.04) discard;
  // The atlas is a white disc with dark text, so the disc takes the tint and the text stays white.
  outColor = vec4(mix(vec3(1.0), v_color, texel.r), texel.a * v_alpha);
}`;

export interface LayerVehicle {
  vehicleId: string;
  /** The route disc label, e.g. "18". */
  label: string;
  /** `#rrggbb`; tints the body and the label disc. */
  colour: string;
  train: boolean;
  /** 1 normally, 0.4 for a stale vehicle, 0.5 for one dimmed by a selection. */
  opacity: number;
  selected: boolean;
}

export interface VehicleLayerOptions {
  clock: () => number;
  onFrame?: ((drawn: readonly DrawnVehicle[], now: number) => void) | undefined;
}

interface VehicleUniforms {
  matrix: WebGLUniformLocation | null;
  meter: WebGLUniformLocation | null;
}

interface LabelUniforms {
  matrix: WebGLUniformLocation | null;
  viewport: WebGLUniformLocation | null;
  cells: WebGLUniformLocation | null;
  halfPx: WebGLUniformLocation | null;
  atlas: WebGLUniformLocation | null;
}

interface Gpu {
  vehicleProgram: WebGLProgram;
  labelProgram: WebGLProgram;
  busVao: WebGLVertexArrayObject;
  trainVao: WebGLVertexArrayObject;
  labelVao: WebGLVertexArrayObject;
  buffers: WebGLBuffer[];
  busInstances: WebGLBuffer;
  trainInstances: WebGLBuffer;
  labelInstances: WebGLBuffer;
  busIndexCount: number;
  trainIndexCount: number;
  atlasTexture: WebGLTexture | null;
  vehicle: VehicleUniforms;
  label: LabelUniforms;
}

function compileShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader | null {
  const shader = gl.createShader(type);
  if (!shader) return null;
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (gl.getShaderParameter(shader, gl.COMPILE_STATUS) !== true) {
    gl.deleteShader(shader);
    return null;
  }
  return shader;
}

function createProgram(gl: WebGL2RenderingContext, vsSource: string, fsSource: string): WebGLProgram | null {
  const vs = compileShader(gl, gl.VERTEX_SHADER, vsSource);
  const fs = compileShader(gl, gl.FRAGMENT_SHADER, fsSource);
  if (!vs || !fs) return null;
  const program = gl.createProgram();
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  if (gl.getProgramParameter(program, gl.LINK_STATUS) !== true) {
    gl.deleteProgram(program);
    return null;
  }
  return program;
}

function uploadStatic(gl: WebGL2RenderingContext, data: Float32Array | Uint8Array | Uint16Array): WebGLBuffer {
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
  return buffer;
}

function uploadIndices(gl: WebGL2RenderingContext, data: Uint16Array): WebGLBuffer {
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, data, gl.STATIC_DRAW);
  return buffer;
}

function setInstanced(gl: WebGL2RenderingContext, attrib: number, size: number, stride: number, offset: number): void {
  gl.enableVertexAttribArray(attrib);
  gl.vertexAttribPointer(attrib, size, gl.FLOAT, false, stride * 4, offset);
  gl.vertexAttribDivisor(attrib, 1);
}

function hexToRgb(hex: string): [number, number, number] {
  const value = Number.parseInt(hex.slice(1), 16);
  return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
}

function ensureCapacity(data: Float32Array, needed: number): Float32Array {
  if (data.length >= needed) return data;
  return new Float32Array(Math.max(needed, data.length * 2, 64));
}

function writeVehicle(
  data: Float32Array,
  index: number,
  mx: number,
  my: number,
  heading: number,
  colour: readonly [number, number, number],
  alpha: number,
  scale: number,
  selected: number,
): number {
  const o = index * VEHICLE_STRIDE;
  data[o] = mx;
  data[o + 1] = my;
  data[o + 2] = heading;
  data[o + 3] = colour[0];
  data[o + 4] = colour[1];
  data[o + 5] = colour[2];
  data[o + 6] = alpha;
  data[o + 7] = scale;
  data[o + 8] = selected;
  return index + 1;
}

function writeLabel(
  data: Float32Array,
  index: number,
  mx: number,
  my: number,
  mz: number,
  colour: readonly [number, number, number],
  alpha: number,
  cell: number,
  scale: number,
): number {
  const o = index * LABEL_STRIDE;
  data[o] = mx;
  data[o + 1] = my;
  data[o + 2] = mz;
  data[o + 3] = colour[0];
  data[o + 4] = colour[1];
  data[o + 5] = colour[2];
  data[o + 6] = alpha;
  data[o + 7] = cell;
  data[o + 8] = scale;
  return index + 1;
}

/** Upload `used` floats into `buffer`, growing the GPU storage only when the tracked capacity is too small. */
function uploadDynamic(
  gl: WebGL2RenderingContext,
  buffer: WebGLBuffer | null,
  data: Float32Array,
  used: number,
  capacity: number,
): number {
  if (!buffer) return capacity;
  const view = data.subarray(0, used);
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  if (view.length > capacity) {
    gl.bufferData(gl.ARRAY_BUFFER, view, gl.DYNAMIC_DRAW);
    return view.length;
  }
  gl.bufferSubData(gl.ARRAY_BUFFER, 0, view);
  return capacity;
}

export class VehicleLayer implements CustomLayerInterface {
  readonly id = 'vehicles-3d';
  readonly type = 'custom' as const;
  readonly renderingMode = '3d' as const;

  private readonly model: MotionModel;
  private readonly clock: () => number;
  private readonly onFrame: ((drawn: readonly DrawnVehicle[], now: number) => void) | undefined;
  private readonly atlas = new LabelAtlas();
  private readonly points: PickPoint[] = [];
  private readonly screen = new Map<string, { x: number; y: number }>();

  private map: MapLibreMap | null = null;
  private gpu: Gpu | null = null;
  private metadata = new Map<string, LayerVehicle>();
  private paused = false;
  private failed = false;

  private busData: Float32Array = new Float32Array(0);
  private trainData: Float32Array = new Float32Array(0);
  private labelData: Float32Array = new Float32Array(0);
  private busFloats = 0;
  private trainFloats = 0;
  private labelFloats = 0;

  constructor(model: MotionModel, options: VehicleLayerOptions) {
    this.model = model;
    this.clock = options.clock;
    this.onFrame = options.onFrame;
  }

  /** Join vehicle metadata with the motion model; a vehicle without metadata is not drawn. */
  setVehicles(items: readonly LayerVehicle[]): void {
    this.metadata = new Map(items.map((item) => [item.vehicleId, item]));
  }

  /** Stop asking for repaints (the controller pauses while the tab is hidden). */
  setPaused(value: boolean): void {
    this.paused = value;
  }

  /** The last drawn screen position of a vehicle (CSS px), or undefined when it is off screen. */
  screenPosition(vehicleId: string): { x: number; y: number } | undefined {
    return this.screen.get(vehicleId);
  }

  /** The vehicle nearest `(x, y)` within 18 px, using the last frame's projection. */
  pick(x: number, y: number): string | undefined {
    return pickNearest(this.points, x, y, PICK_RADIUS_PX);
  }

  onAdd(map: MapLibreMap, gl: WebGL2RenderingContext): void {
    this.map = map;
    if (gl.isContextLost()) return;
    const vehicleProgram = createProgram(gl, VEHICLE_VS, VEHICLE_FS);
    const labelProgram = createProgram(gl, LABEL_VS, LABEL_FS);
    if (!vehicleProgram || !labelProgram) return;

    const bus = busMesh();
    const train = trainMesh();
    const buffers: WebGLBuffer[] = [];
    const make = (data: Float32Array | Uint8Array): WebGLBuffer => {
      const buffer = uploadStatic(gl, data);
      buffers.push(buffer);
      return buffer;
    };
    const makeIndices = (data: Uint16Array): WebGLBuffer => {
      const buffer = uploadIndices(gl, data);
      buffers.push(buffer);
      return buffer;
    };

    const busPositions = make(bus.positions);
    const busRoles = make(bus.roles);
    const busIndices = makeIndices(bus.indices);
    const trainPositions = make(train.positions);
    const trainRoles = make(train.roles);
    const trainIndices = makeIndices(train.indices);
    const labelCorners = make(LABEL_QUAD);
    const labelIndices = makeIndices(LABEL_QUAD_INDICES);
    const busInstances = make(new Float32Array(0));
    const trainInstances = make(new Float32Array(0));
    const labelInstances = make(new Float32Array(0));

    const busVao = this.makeVehicleVao(gl, vehicleProgram, {
      positions: busPositions,
      roles: busRoles,
      indices: busIndices,
      instances: busInstances,
    });
    const trainVao = this.makeVehicleVao(gl, vehicleProgram, {
      positions: trainPositions,
      roles: trainRoles,
      indices: trainIndices,
      instances: trainInstances,
    });
    const labelVao = this.makeLabelVao(gl, labelProgram, labelCorners, labelIndices, labelInstances);

    gl.bindVertexArray(null);
    const atlasTexture = this.atlas.upload(gl);

    this.gpu = {
      vehicleProgram,
      labelProgram,
      busVao,
      trainVao,
      labelVao,
      buffers,
      busInstances,
      trainInstances,
      labelInstances,
      busIndexCount: bus.indices.length,
      trainIndexCount: train.indices.length,
      atlasTexture,
      vehicle: {
        matrix: gl.getUniformLocation(vehicleProgram, 'u_matrix'),
        meter: gl.getUniformLocation(vehicleProgram, 'u_meter'),
      },
      label: {
        matrix: gl.getUniformLocation(labelProgram, 'u_matrix'),
        viewport: gl.getUniformLocation(labelProgram, 'u_viewport'),
        cells: gl.getUniformLocation(labelProgram, 'u_cells'),
        halfPx: gl.getUniformLocation(labelProgram, 'u_half_px'),
        atlas: gl.getUniformLocation(labelProgram, 'u_atlas'),
      },
    };
  }

  render(gl: WebGL2RenderingContext, options: CustomRenderMethodInput): void {
    if (!this.gpu || this.failed || gl.isContextLost()) return;
    try {
      this.draw(gl, options);
    } catch {
      // A lost context or a stale program: skip this frame instead of throwing on every repaint.
    }
    if (!this.paused) this.map?.triggerRepaint();
  }

  onRemove(_map: MapLibreMap, gl: WebGL2RenderingContext): void {
    const gpu = this.gpu;
    if (gpu) {
      gl.deleteProgram(gpu.vehicleProgram);
      gl.deleteProgram(gpu.labelProgram);
      gl.deleteVertexArray(gpu.busVao);
      gl.deleteVertexArray(gpu.trainVao);
      gl.deleteVertexArray(gpu.labelVao);
      for (const buffer of gpu.buffers) gl.deleteBuffer(buffer);
    }
    this.atlas.dispose(gl);
    this.gpu = null;
    this.map = null;
  }

  private makeVehicleVao(
    gl: WebGL2RenderingContext,
    program: WebGLProgram,
    buffers: { positions: WebGLBuffer; roles: WebGLBuffer; indices: WebGLBuffer; instances: WebGLBuffer },
  ): WebGLVertexArrayObject {
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffers.positions);
    const local = gl.getAttribLocation(program, 'a_local');
    gl.enableVertexAttribArray(local);
    gl.vertexAttribPointer(local, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffers.roles);
    const role = gl.getAttribLocation(program, 'a_role');
    gl.enableVertexAttribArray(role);
    gl.vertexAttribPointer(role, 1, gl.UNSIGNED_BYTE, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, buffers.indices);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffers.instances);
    setInstanced(gl, gl.getAttribLocation(program, 'a_offset'), 2, VEHICLE_STRIDE, 0);
    setInstanced(gl, gl.getAttribLocation(program, 'a_heading'), 1, VEHICLE_STRIDE, 8);
    setInstanced(gl, gl.getAttribLocation(program, 'a_color'), 3, VEHICLE_STRIDE, 12);
    setInstanced(gl, gl.getAttribLocation(program, 'a_alpha'), 1, VEHICLE_STRIDE, 24);
    setInstanced(gl, gl.getAttribLocation(program, 'a_scale'), 1, VEHICLE_STRIDE, 28);
    setInstanced(gl, gl.getAttribLocation(program, 'a_flags'), 1, VEHICLE_STRIDE, 32);
    return vao;
  }

  private makeLabelVao(
    gl: WebGL2RenderingContext,
    program: WebGLProgram,
    corners: WebGLBuffer,
    indices: WebGLBuffer,
    instances: WebGLBuffer,
  ): WebGLVertexArrayObject {
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, corners);
    const corner = gl.getAttribLocation(program, 'a_corner');
    gl.enableVertexAttribArray(corner);
    gl.vertexAttribPointer(corner, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indices);
    gl.bindBuffer(gl.ARRAY_BUFFER, instances);
    setInstanced(gl, gl.getAttribLocation(program, 'a_offset'), 3, LABEL_STRIDE, 0);
    setInstanced(gl, gl.getAttribLocation(program, 'a_color'), 3, LABEL_STRIDE, 12);
    setInstanced(gl, gl.getAttribLocation(program, 'a_alpha'), 1, LABEL_STRIDE, 24);
    setInstanced(gl, gl.getAttribLocation(program, 'a_cell'), 1, LABEL_STRIDE, 28);
    setInstanced(gl, gl.getAttribLocation(program, 'a_label_scale'), 1, LABEL_STRIDE, 32);
    return vao;
  }

  private draw(gl: WebGL2RenderingContext, options: CustomRenderMethodInput): void {
    const gpu = this.gpu;
    const map = this.map;
    if (!gpu || !map) return;

    const now = this.clock();
    const drawn = this.model.frame(now);
    this.onFrame?.(drawn, now);

    const canvas = map.getCanvas();
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    const matrix = options.defaultProjectionData.mainMatrix;
    const centerLat = map.getCenter().lat;
    const zoom = map.getZoom();
    const mpp = metresPerPixel(zoom, centerLat);
    const meter = mercatorUnitsPerMetre(centerLat);
    const busScale = Math.max(1, (MIN_LENGTH_PX * mpp) / BUS_LENGTH_M);
    const trainScale = Math.max(1, (MIN_LENGTH_PX * mpp) / TRAIN_LENGTH_M);
    const showLabels = zoom >= LABEL_ZOOM;

    this.busData = ensureCapacity(this.busData, drawn.length * VEHICLE_STRIDE);
    this.trainData = ensureCapacity(this.trainData, drawn.length * VEHICLE_STRIDE);
    this.labelData = ensureCapacity(this.labelData, drawn.length * LABEL_STRIDE);
    this.points.length = 0;
    this.screen.clear();

    let busCount = 0;
    let trainCount = 0;
    let labelCount = 0;
    for (const vehicle of drawn) {
      const meta = this.metadata.get(vehicle.vehicleId);
      if (!meta) continue;
      const [mx, my] = mercatorXY(vehicle.lon, vehicle.lat);
      const heading = (vehicle.heading * Math.PI) / 180;
      const colour = hexToRgb(meta.colour);
      const alpha = vehicle.alpha * meta.opacity;
      const selected = meta.selected ? 1 : 0;
      if (meta.train) {
        trainCount = writeVehicle(this.trainData, trainCount, mx, my, heading, colour, alpha, trainScale, selected);
      } else {
        busCount = writeVehicle(this.busData, busCount, mx, my, heading, colour, alpha, busScale, selected);
      }
      if (showLabels) {
        const mz = LABEL_ALTITUDE_M * mercatorUnitsPerMetre(vehicle.lat);
        const cell = this.atlas.cellOf(meta.label);
        labelCount = writeLabel(this.labelData, labelCount, mx, my, mz, colour, alpha, cell, meta.selected ? 1.35 : 1);
      }
      const point = projectToScreen(matrix, vehicle.lon, vehicle.lat, 0, width, height);
      if (point.visible) {
        this.points.push({ id: vehicle.vehicleId, x: point.x, y: point.y, depth: point.depth });
        this.screen.set(vehicle.vehicleId, { x: point.x, y: point.y });
      }
    }

    gl.enable(gl.DEPTH_TEST);
    gl.depthFunc(gl.LEQUAL);
    gl.depthMask(true);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.disable(gl.CULL_FACE);

    gl.useProgram(gpu.vehicleProgram);
    gl.uniformMatrix4fv(gpu.vehicle.matrix, false, matrix as Float32Array);
    gl.uniform1f(gpu.vehicle.meter, meter);
    if (busCount > 0) {
      this.busFloats = uploadDynamic(gl, gpu.busInstances, this.busData, busCount * VEHICLE_STRIDE, this.busFloats);
      gl.bindVertexArray(gpu.busVao);
      gl.drawElementsInstanced(gl.TRIANGLES, gpu.busIndexCount, gl.UNSIGNED_SHORT, 0, busCount);
    }
    if (trainCount > 0) {
      this.trainFloats = uploadDynamic(
        gl,
        gpu.trainInstances,
        this.trainData,
        trainCount * VEHICLE_STRIDE,
        this.trainFloats,
      );
      gl.bindVertexArray(gpu.trainVao);
      gl.drawElementsInstanced(gl.TRIANGLES, gpu.trainIndexCount, gl.UNSIGNED_SHORT, 0, trainCount);
    }
    gl.bindVertexArray(null);

    if (labelCount > 0) {
      if (this.atlas.dirty) gpu.atlasTexture = this.atlas.upload(gl) ?? gpu.atlasTexture;
      gl.useProgram(gpu.labelProgram);
      gl.uniformMatrix4fv(gpu.label.matrix, false, matrix as Float32Array);
      gl.uniform2f(gpu.label.viewport, width, height);
      gl.uniform2f(gpu.label.cells, this.atlas.columns, this.atlas.rows);
      gl.uniform1f(gpu.label.halfPx, LABEL_HALF_PX);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, gpu.atlasTexture);
      gl.uniform1i(gpu.label.atlas, 0);
      gl.depthMask(false);
      this.labelFloats = uploadDynamic(
        gl,
        gpu.labelInstances,
        this.labelData,
        labelCount * LABEL_STRIDE,
        this.labelFloats,
      );
      gl.bindVertexArray(gpu.labelVao);
      gl.drawElementsInstanced(gl.TRIANGLES, LABEL_QUAD_INDICES.length, gl.UNSIGNED_SHORT, 0, labelCount);
      gl.bindVertexArray(null);
      gl.depthMask(true);
    }
  }
}
