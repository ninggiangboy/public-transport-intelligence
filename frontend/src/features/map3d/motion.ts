// Pure motion model for the 3D live map (DR-111 "Chuyển động", ported from the S-07 spike
// `motion.js`). No DOM, no WebGL and no React, so the same code runs in the custom layer, in
// Follow camera mode and in the tests. A vehicle is tracked as a distance `along` its route shape:
// each GPS fix is projected back onto the shape, so the model never trusts GPS heading and a
// vehicle can never drift sideways off its road.

const EARTH_RADIUS_M = 6378137;
const M_PER_DEG_LAT = (2 * Math.PI * EARTH_RADIUS_M) / 360;

// DR-111 tunables (see the decision register, "Chuyển động" rules 1-6).
const OFF_SHAPE_M = 50; // farther than this the vehicle dead-reckons on its bearing (rule 5)
const JUMP_M = 150; // a fix farther than this from the drawn point fades, never slides (rule 4)
const MAX_AGE_S = 20; // no fix for this long: stop estimating (rule 2)
const BLEND_S = 1; // an ahead-fix is blended in over this long (rule 3)
const FADE_S = 0.15; // fade out/in duration of a jump or trip change (rule 4)
const FADE_MID_MS = (FADE_S / 2) * 1000; // the position switches here, while the vehicle is invisible

/** One live fix for a vehicle. `at` is the fix event time in ms. */
export interface FixInput {
  vehicleId: string;
  tripId: string;
  routeId: string;
  directionId: number;
  lon: number;
  lat: number;
  speedMps?: number | undefined;
  bearing?: number | undefined;
  currentStatus: string;
  at: number;
}

/** A route shape as [lon, lat] points. */
export type Shape = readonly (readonly [number, number])[];

/** A shape with its cumulative distances, built once by {@link indexShape}. */
export interface ShapeIndex {
  shape: Shape;
  cumulative: Float64Array;
}

/** Candidate shapes per route/direction; the model picks the nearest within 50 m. */
export interface Shapes {
  forRoute(routeId: string, directionId: number): readonly ShapeIndex[];
}

/** A vehicle position ready to draw. Positions are estimates between fixes. */
export interface DrawnVehicle {
  vehicleId: string;
  lon: number;
  lat: number;
  /** Degrees clockwise from north, from the shape direction when a shape is used. */
  heading: number;
  /** 1 normally; dips to 0 and back on a jump / trip change (or on every change in reduced motion). */
  alpha: number;
  estimated: boolean;
  fixAgeSeconds: number;
  lastFix: { lon: number; lat: number };
}

function metresPerDegreeLon(lat: number): number {
  return M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180);
}

/** Metres between two [lon, lat] points (equirectangular; exact at city scale). */
export function distanceMetres(a: readonly [number, number], b: readonly [number, number]): number {
  const lat = ((a[1] + b[1]) / 2) * (Math.PI / 180);
  return Math.hypot((b[0] - a[0]) * metresPerDegreeLon(lat), (b[1] - a[1]) * M_PER_DEG_LAT);
}

/** Cumulative distances along a shape, for projection and interpolation. */
export function indexShape(shape: Shape): ShapeIndex {
  const cumulative = new Float64Array(shape.length);
  let total = 0;
  for (let i = 1; i < shape.length; i++) {
    const a = shape.at(i - 1);
    const b = shape.at(i);
    if (a && b) total += distanceMetres(a, b);
    cumulative[i] = total;
  }
  return { shape, cumulative };
}

function totalOf(cumulative: Float64Array): number {
  return cumulative.length > 0 ? (cumulative.at(cumulative.length - 1) ?? 0) : 0;
}

function segmentAt(cumulative: Float64Array, along: number): { i: number; a: number } {
  const a = Math.max(0, Math.min(along, totalOf(cumulative)));
  let i = 1;
  while (i < cumulative.length - 1 && (cumulative.at(i) ?? 0) < a) i++;
  return { i, a };
}

/** [lon, lat] at `along` metres from the shape start. */
export function pointAt(index: ShapeIndex, along: number): [number, number] {
  const first = index.shape.at(0);
  if (!first) return [0, 0];
  if (index.shape.length < 2) return [first[0], first[1]];
  const { i, a } = segmentAt(index.cumulative, along);
  const p0 = index.shape.at(i - 1);
  const p1 = index.shape.at(i);
  if (!p0 || !p1) return [first[0], first[1]];
  const c0 = index.cumulative.at(i - 1) ?? 0;
  const segLen = (index.cumulative.at(i) ?? 0) - c0 || 1;
  const t = (a - c0) / segLen;
  return [p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t];
}

/** Bearing in degrees (0 = north, clockwise) of the shape segment at `along`. */
export function headingAt(index: ShapeIndex, along: number): number {
  const { i } = segmentAt(index.cumulative, along);
  const p0 = index.shape.at(i - 1);
  const p1 = index.shape.at(i);
  if (!p0 || !p1) return 0;
  const lat = ((p0[1] + p1[1]) / 2) * (Math.PI / 180);
  const dx = (p1[0] - p0[0]) * metresPerDegreeLon(lat);
  const dy = (p1[1] - p0[1]) * M_PER_DEG_LAT;
  return ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360;
}

/** Nearest point on the shape: travelled distance and off-shape error, in metres. */
export function projectOntoShape(
  index: ShapeIndex,
  lonlat: readonly [number, number],
): { along: number; distance: number } {
  const { shape, cumulative } = index;
  let along = 0;
  let distance = Infinity;
  for (let i = 1; i < shape.length; i++) {
    const a = shape.at(i - 1);
    const b = shape.at(i);
    if (!a || !b) continue;
    const lat = ((a[1] + b[1]) / 2) * (Math.PI / 180);
    const kx = metresPerDegreeLon(lat);
    const ax = (lonlat[0] - a[0]) * kx;
    const ay = (lonlat[1] - a[1]) * M_PER_DEG_LAT;
    const bx = (b[0] - a[0]) * kx;
    const by = (b[1] - a[1]) * M_PER_DEG_LAT;
    const len2 = bx * bx + by * by || 1;
    const t = Math.max(0, Math.min(1, (ax * bx + ay * by) / len2));
    const d = Math.hypot(ax - bx * t, ay - by * t);
    if (d < distance) {
      distance = d;
      along = (cumulative.at(i - 1) ?? 0) + t * distanceMetres(a, b);
    }
  }
  return { along, distance: distance === Infinity ? 0 : distance };
}

interface Body {
  vehicleId: string;
  tripId: string;
  status: string;
  speedMps: number;
  bearing: number;
  straight: boolean;
  index: ShapeIndex;
  origin: readonly [number, number];
  drawnAlong: number;
  estimateAlong: number;
  blend: { from: number; startT: number } | null;
  fadeStartT: number | null;
  /** A jump waits for the middle of the fade before the vehicle moves (rule 4). */
  pendingAlong: { along: number; at: number } | null;
  /** Reduced motion: the fix being drawn, and the next one waiting for the middle of the fade. */
  shown: { lon: number; lat: number };
  pendingShown: { lon: number; lat: number; at: number } | null;
  lastFixAt: number;
  lastFixLon: number;
  lastFixLat: number;
  hasFix: boolean;
  out: DrawnVehicle | null;
}

const EMPTY_INDEX = indexShape([]);

/** [lon, lat] `d` metres from `origin` along `bearing` degrees. */
function stepAlong(origin: readonly [number, number], bearing: number, d: number): [number, number] {
  const rad = (bearing * Math.PI) / 180;
  const lat = origin[1] + (Math.cos(rad) * d) / M_PER_DEG_LAT;
  const kx = metresPerDegreeLon((origin[1] + lat) / 2);
  return [origin[0] + (Math.sin(rad) * d) / kx, lat];
}

function currentPoint(body: Body): [number, number] {
  return body.straight ? stepAlong(body.origin, body.bearing, body.drawnAlong) : pointAt(body.index, body.drawnAlong);
}

/** Signed distance of a fix along the dead-reckoned ray, plus its lateral error. */
function projectStraight(body: Body, lon: number, lat: number): { along: number; distance: number } {
  const kx = metresPerDegreeLon((body.origin[1] + lat) / 2);
  const dx = (lon - body.origin[0]) * kx;
  const dy = (lat - body.origin[1]) * M_PER_DEG_LAT;
  const rad = (body.bearing * Math.PI) / 180;
  const ex = Math.sin(rad);
  const en = Math.cos(rad);
  const along = dx * ex + dy * en;
  return { along, distance: Math.hypot(dx - along * ex, dy - along * en) };
}

/**
 * Decide the path for a fix (rules 1 and 5): the nearest route shape within 50 m, otherwise a
 * straight ray from the drawn point along the fix bearing. Re-anchoring keeps the drawn point
 * where it is when the geometry underneath it changes.
 */
function selectPath(body: Body, fix: FixInput, shapes: Shapes, isNew: boolean): void {
  let best: ShapeIndex | null = null;
  let bestDist = Infinity;
  for (const candidate of shapes.forRoute(fix.routeId, fix.directionId)) {
    const p = projectOntoShape(candidate, [fix.lon, fix.lat]);
    if (p.distance < bestDist) {
      bestDist = p.distance;
      best = candidate;
    }
  }
  if (best && bestDist <= OFF_SHAPE_M) {
    if (!isNew && (body.straight || best !== body.index)) {
      const at = currentPoint(body);
      body.drawnAlong = projectOntoShape(best, at).along;
      body.estimateAlong = body.drawnAlong;
    }
    body.straight = false;
    body.index = best;
    return;
  }
  if (!isNew) {
    const at = currentPoint(body);
    body.origin = at;
    body.drawnAlong = 0;
    body.estimateAlong = 0;
  }
  body.straight = true;
  body.bearing = fix.bearing ?? body.bearing;
}

function applyFix(body: Body, fix: FixInput, now: number, shapes: Shapes, reduced: boolean, isNew: boolean): void {
  body.status = fix.currentStatus;

  if (reduced) {
    // Reduced motion: no estimation, no blending. Draw the raw fix and fade on each change.
    body.lastFixLon = fix.lon;
    body.lastFixLat = fix.lat;
    body.bearing = fix.bearing ?? body.bearing;
    body.lastFixAt = fix.at;
    body.hasFix = true;
    if (isNew) {
      body.shown = { lon: fix.lon, lat: fix.lat };
    } else {
      body.fadeStartT = now;
      body.pendingShown = { lon: fix.lon, lat: fix.lat, at: now + FADE_MID_MS };
    }
    return;
  }

  selectPath(body, fix, shapes, isNew);
  const projected = body.straight
    ? projectStraight(body, fix.lon, fix.lat)
    : projectOntoShape(body.index, [fix.lon, fix.lat]);

  const tripChanged = !isNew && fix.tripId !== body.tripId;
  const delta = projected.along - body.drawnAlong;

  if (isNew) {
    body.drawnAlong = projected.along;
    body.estimateAlong = projected.along;
  } else if (tripChanged || Math.abs(delta) > JUMP_M) {
    // Rule 4: fade out/in at the fix; the position switches with the fade, it never slides.
    body.pendingAlong = { along: projected.along, at: now + FADE_MID_MS };
    body.blend = null;
    body.fadeStartT = now;
  } else if (delta < 0) {
    // Rule 3: the fix is behind; hold the drawn point until the estimate walks past it.
    body.estimateAlong = projected.along;
    body.blend = null;
  } else {
    // Rule 3: an ahead-fix is blended in over BLEND_S.
    body.estimateAlong = projected.along;
    body.blend = { from: body.drawnAlong, startT: now };
  }

  if (fix.speedMps != null) body.speedMps = fix.speedMps;
  if (fix.bearing != null) body.bearing = fix.bearing;
  body.tripId = fix.tripId;
  body.lastFixLon = fix.lon;
  body.lastFixLat = fix.lat;
  body.lastFixAt = fix.at;
  body.hasFix = true;
}

/** One frame (rules 2-3): walk the estimate, follow it without ever moving backwards. */
function advance(body: Body, now: number, dt: number): void {
  if (body.pendingAlong && now >= body.pendingAlong.at) {
    body.drawnAlong = body.pendingAlong.along;
    body.estimateAlong = body.pendingAlong.along;
    body.pendingAlong = null;
  }
  const fresh = body.hasFix && now - body.lastFixAt <= MAX_AGE_S * 1000 && body.status !== 'STOPPED_AT';
  if (fresh) body.estimateAlong += body.speedMps * dt;

  let desired = body.estimateAlong;
  const blend = body.blend;
  if (blend) {
    const t = Math.min(1, (now - blend.startT) / 1000 / BLEND_S);
    desired = blend.from + (body.estimateAlong - blend.from) * t;
    if (t >= 1) body.blend = null;
  }
  if (desired > body.drawnAlong) body.drawnAlong = desired;
}

function alphaOf(fadeStartT: number | null, now: number): number {
  if (fadeStartT === null) return 1;
  const t = (now - fadeStartT) / 1000 / FADE_S;
  if (t <= 0 || t >= 1) return 1;
  return t < 0.5 ? 1 - 2 * t : 2 * t - 1;
}

function buildOut(body: Body, now: number, reduced: boolean): DrawnVehicle {
  let out = body.out;
  if (!out) {
    out = {
      vehicleId: body.vehicleId,
      lon: 0,
      lat: 0,
      heading: 0,
      alpha: 1,
      estimated: false,
      fixAgeSeconds: 0,
      lastFix: { lon: 0, lat: 0 },
    };
    body.out = out;
  }
  if (reduced) {
    if (body.pendingShown && now >= body.pendingShown.at) {
      body.shown = { lon: body.pendingShown.lon, lat: body.pendingShown.lat };
      body.pendingShown = null;
    }
    out.lon = body.shown.lon;
    out.lat = body.shown.lat;
    out.heading = body.bearing;
    out.estimated = false;
  } else {
    const p = currentPoint(body);
    out.lon = p[0];
    out.lat = p[1];
    out.heading = body.straight ? body.bearing : headingAt(body.index, body.drawnAlong);
    out.estimated = body.hasFix;
  }
  out.alpha = alphaOf(body.fadeStartT, now);
  out.fixAgeSeconds = body.hasFix ? (now - body.lastFixAt) / 1000 : 0;
  out.lastFix.lon = body.lastFixLon;
  out.lastFix.lat = body.lastFixLat;
  return out;
}

/** The motion model: feed it fixes, read drawn positions each frame. */
export class MotionModel {
  private readonly shapes: Shapes;
  private reduced: boolean;
  private readonly vehicles = new Map<string, Body>();
  private readonly buffer: DrawnVehicle[] = [];
  private time = 0;

  constructor(options: { shapes: Shapes; reduced?: boolean }) {
    this.shapes = options.shapes;
    this.reduced = options.reduced ?? false;
  }

  setReduced(value: boolean): void {
    this.reduced = value;
  }

  /** Reconcile the tracked vehicles with `fixes`: add, update, drop the absent ones. */
  update(fixes: readonly FixInput[], now: number): void {
    const seen = new Set<string>();
    for (const fix of fixes) {
      seen.add(fix.vehicleId);
      let body = this.vehicles.get(fix.vehicleId);
      const isNew = body === undefined;
      if (!body) {
        body = {
          vehicleId: fix.vehicleId,
          tripId: fix.tripId,
          status: fix.currentStatus,
          speedMps: fix.speedMps ?? 0,
          bearing: fix.bearing ?? 0,
          straight: true,
          index: EMPTY_INDEX,
          origin: [fix.lon, fix.lat],
          drawnAlong: 0,
          estimateAlong: 0,
          blend: null,
          fadeStartT: null,
          pendingAlong: null,
          shown: { lon: fix.lon, lat: fix.lat },
          pendingShown: null,
          lastFixAt: -Infinity,
          lastFixLon: fix.lon,
          lastFixLat: fix.lat,
          hasFix: false,
          out: null,
        };
        this.vehicles.set(fix.vehicleId, body);
      } else if (fix.at <= body.lastFixAt) {
        continue; // an older fix than the one already applied
      }
      applyFix(body, fix, now, this.shapes, this.reduced, isNew);
    }
    for (const id of this.vehicles.keys()) {
      if (!seen.has(id)) this.vehicles.delete(id);
    }
    this.time = now;
  }

  /** Advance every vehicle to `now` and return the drawn positions (valid until the next frame). */
  frame(now: number): readonly DrawnVehicle[] {
    const dt = Math.max(0, now - this.time) / 1000;
    this.time = now;
    const out = this.buffer;
    out.length = 0;
    for (const body of this.vehicles.values()) {
      if (!this.reduced) advance(body, now, dt);
      out.push(buildOut(body, now, this.reduced));
    }
    return out;
  }

  /** The last drawn position of a vehicle, or undefined when it is not tracked. */
  get(vehicleId: string): DrawnVehicle | undefined {
    const body = this.vehicles.get(vehicleId);
    return body ? buildOut(body, this.time, this.reduced) : undefined;
  }
}
