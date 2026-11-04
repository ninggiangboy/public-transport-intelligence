// (a) MapLibre CustomLayerInterface on WebGL2: one instanced draw of 600 boxes.
// No library beyond maplibre itself; the box mesh, the instance buffer and the shader live here.
// Per frame the instance buffer is rewritten from the shared motion module and re-uploaded.

import { advance, alphaOf, applyFix, createVehicleState, mercatorUnitsPerMetre, mercatorXY, positionOf } from './motion.js';

const BUS = { width: 2.6, length: 12, height: 3.4 }; // metres

// Unit box: x,y in [-0.5, 0.5], z in [0, 1] so the base sits on the ground.
const C = [
  [-0.5, -0.5, 0], [0.5, -0.5, 0], [0.5, 0.5, 0], [-0.5, 0.5, 0],
  [-0.5, -0.5, 1], [0.5, -0.5, 1], [0.5, 0.5, 1], [-0.5, 0.5, 1],
];
const TRI = [
  [0, 1, 2], [0, 2, 3], [4, 5, 6], [4, 6, 7],
  [0, 1, 5], [0, 5, 4], [1, 2, 6], [1, 6, 5],
  [2, 3, 7], [2, 7, 6], [3, 0, 4], [3, 4, 7],
];
const BOX = new Float32Array(TRI.flatMap((f) => f.flatMap((i) => C[i])));

const VS = `#version 300 es
in vec3 a_local;
in vec3 a_offset;
in float a_yaw;
in vec3 a_color;
in float a_alpha;
in vec3 a_size;
uniform mat4 u_matrix;
uniform float u_meter;
out vec3 v_color;
out float v_alpha;
out float v_z;
void main() {
  vec3 p = a_local * a_size;
  float c = cos(a_yaw), s = sin(a_yaw);
  // rotate in ENU (x east, y north), then flip y for mercator (y grows south)
  vec3 r = vec3(p.x * c + p.y * s, -p.x * s + p.y * c, p.z);
  vec3 merc = a_offset + vec3(r.x, -r.y, r.z) * u_meter;
  gl_Position = u_matrix * vec4(merc, 1.0);
  v_color = a_color;
  v_alpha = a_alpha;
  v_z = a_local.z;
}`;

const FS = `#version 300 es
precision mediump float;
in vec3 v_color;
in float v_alpha;
in float v_z;
out vec4 outColor;
void main() {
  float shade = v_z > 0.9 ? 1.0 : 0.72; // cheap top/side shading
  outColor = vec4(v_color * shade, v_alpha);
}`;

const STRIDE = 11 * 4; // offset(3) + yaw(1) + color(3) + alpha(1) + size(3) floats

export function start({ map, vehicles, motion, now: nowFn }) {
  const count = vehicles.length;
  const states = vehicles.map((v) =>
    createVehicleState({ shape: v.shape, along: v.along, speedMps: v.speedMps, tripId: v.tripId }));
  const nextFix = new Array(count).fill(0);
  const instances = new Float32Array(count * 11);

  let program = null;
  let vao = null;
  let boxBuffer = null;
  let instanceBuffer = null;
  const loc = {};
  const attrib = {};
  let last = nowFn();
  let raf = 0;

  function compile(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(`shader: ${gl.getShaderInfoLog(shader)}`);
    }
    return shader;
  }

  function updateInstances(t, dt) {
    for (let i = 0; i < count; i++) {
      const v = vehicles[i];
      const s = states[i];
      while (nextFix[i] < v.fixes.length && v.fixes[nextFix[i]].t <= t) {
        applyFix(s, v.fixes[nextFix[i]], t);
        nextFix[i]++;
      }
      advance(s, t, dt);
      const [lon, lat] = positionOf(s);
      const [mx, my] = mercatorXY(lon, lat);
      const o = i * 11;
      instances[o] = mx;
      instances[o + 1] = my;
      instances[o + 2] = 0;
      instances[o + 3] = (s.heading * Math.PI) / 180;
      instances[o + 4] = v.color[0] / 255;
      instances[o + 5] = v.color[1] / 255;
      instances[o + 6] = v.color[2] / 255;
      instances[o + 7] = alphaOf(s, t);
      instances[o + 8] = BUS.width;
      instances[o + 9] = BUS.length;
      instances[o + 10] = BUS.height;
    }
  }

  const layer = {
    id: 's07-vehicles-custom',
    type: 'custom',
    renderingMode: '3d',
    onAdd(_map, gl) {
      const vs = compile(gl, gl.VERTEX_SHADER, VS);
      const fs = compile(gl, gl.FRAGMENT_SHADER, FS);
      program = gl.createProgram();
      gl.attachShader(program, vs);
      gl.attachShader(program, fs);
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error(`link: ${gl.getProgramInfoLog(program)}`);
      }
      loc.matrix = gl.getUniformLocation(program, 'u_matrix');
      loc.meter = gl.getUniformLocation(program, 'u_meter');
      for (const name of ['a_local', 'a_offset', 'a_yaw', 'a_color', 'a_alpha', 'a_size']) {
        attrib[name] = gl.getAttribLocation(program, name);
      }
      boxBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, boxBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, BOX, gl.STATIC_DRAW);
      instanceBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, instances.byteLength, gl.DYNAMIC_DRAW);

      vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      gl.bindBuffer(gl.ARRAY_BUFFER, boxBuffer);
      gl.enableVertexAttribArray(attrib.a_local);
      gl.vertexAttribPointer(attrib.a_local, 3, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuffer);
      const set = (name, size, offset) => {
        gl.enableVertexAttribArray(attrib[name]);
        gl.vertexAttribPointer(attrib[name], size, gl.FLOAT, false, STRIDE, offset);
        gl.vertexAttribDivisor(attrib[name], 1);
      };
      set('a_offset', 3, 0);
      set('a_yaw', 1, 12);
      set('a_color', 3, 16);
      set('a_alpha', 1, 28);
      set('a_size', 3, 32);
      gl.bindVertexArray(null);
    },
    render(gl, options) {
      if (!program) return;
      // MapLibre 6 passes {..., defaultProjectionData.mainMatrix}; it maps web-mercator [0,1] x/y
      // (and a conformal z in mercator units) to clip space.
      const matrix = options.defaultProjectionData?.mainMatrix ?? options.modelViewProjectionMatrix;
      gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuffer);
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, instances);
      gl.useProgram(program);
      gl.uniformMatrix4fv(loc.matrix, false, matrix);
      gl.uniform1f(loc.meter, mercatorUnitsPerMetre(map.getCenter().lat));
      gl.enable(gl.DEPTH_TEST);
      gl.depthFunc(gl.LEQUAL);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.disable(gl.CULL_FACE);
      gl.bindVertexArray(vao);
      gl.drawArraysInstanced(gl.TRIANGLES, 0, 36, count);
      gl.bindVertexArray(null);
    },
    onRemove(_map, gl) {
      if (boxBuffer) gl.deleteBuffer(boxBuffer);
      if (instanceBuffer) gl.deleteBuffer(instanceBuffer);
      if (vao) gl.deleteVertexArray(vao);
      if (program) gl.deleteProgram(program);
      program = null;
    },
  };

  map.addLayer(layer);

  function frame() {
    const t = nowFn();
    const dt = Math.min(0.1, t - last);
    last = t;
    updateInstances(t, dt);
    map.triggerRepaint();
    raf = requestAnimationFrame(frame);
  }
  raf = requestAnimationFrame(frame);

  return {
    stop() {
      cancelAnimationFrame(raf);
      if (map.getLayer(layer.id)) map.removeLayer(layer.id);
    },
    positions: () => states.map((s) => positionOf(s)),
  };
}
