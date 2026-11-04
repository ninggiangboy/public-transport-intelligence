// Shared motion model for the S-07 spike (DR-111 "Chuyển động", DOC-35 §6.4).
//
// Pure functions, no DOM and no library imports, so the exact same code runs in the browser
// and under node (motion.check.mjs). Both implementations (custom WebGL layer, deck.gl overlay)
// only call applyFix() / advance() and never move a vehicle themselves.
//
// A vehicle is tracked as a distance `along` (metres) travelled on its route shape. Fixes are
// lon/lat that get projected back onto the shape, so the model never trusts GPS heading and a
// vehicle can never drift sideways off its road.

const EARTH_RADIUS_M = 6378137;
const EARTH_CIRCUMFERENCE_M = 2 * Math.PI * EARTH_RADIUS_M;
const M_PER_DEG_LAT = EARTH_CIRCUMFERENCE_M / 360;

/** Tunables fixed by DR-111; exported so both renderers and the self-check share them. */
export const RULES = {
  /** A fix farther than this from the drawn position is a jump: fade out/in, never slide. */
  jumpDistanceM: 150,
  /** No fix for this long and the estimate stops advancing. */
  maxEstimateAgeS: 20,
  /** A fix ahead of the drawn position is blended in over this long. */
  blendSeconds: 1,
  /** Fade out/in duration of a jump or trip change. */
  fadeSeconds: 0.15,
};

function metresPerDegreeLon(lat) {
  return M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180);
}

/** Metres between two [lon, lat] points (equirectangular; exact enough at city scale). */
export function distanceM(a, b) {
  const lat = ((a[1] + b[1]) / 2) * (Math.PI / 180);
  return Math.hypot((b[0] - a[0]) * metresPerDegreeLon(lat), (b[1] - a[1]) * M_PER_DEG_LAT);
}

/** Mercator units per metre at a latitude; the MapLibre custom layer needs it to size its meshes. */
export function mercatorUnitsPerMetre(lat) {
  return 1 / (EARTH_CIRCUMFERENCE_M * Math.cos((lat * Math.PI) / 180));
}

/** Web-Mercator [0,1] x/y of a lon/lat (matches maplibre-gl MercatorCoordinate). */
export function mercatorXY(lon, lat) {
  return [
    lon / 360 + 0.5,
    0.5 - (Math.log(Math.tan(Math.PI * 0.25 + 0.5 * lat * (Math.PI / 180))) / Math.PI) * 0.5,
  ];
}

/** Cumulative distances of a shape. */
export function shapeLengths(shape) {
  const cum = [0];
  let total = 0;
  for (let i = 1; i < shape.length; i++) {
    total += distanceM(shape[i - 1], shape[i]);
    cum.push(total);
  }
  return { cum, total };
}

function segmentAt(cum, along) {
  const total = cum[cum.length - 1];
  const a = Math.max(0, Math.min(along, total));
  let i = 1;
  while (i < cum.length - 1 && cum[i] < a) i++;
  return { i, a };
}

/** [lon, lat] at `along` metres from the shape start. */
export function pointAt(shape, cum, along) {
  const { i, a } = segmentAt(cum, along);
  const segLen = cum[i] - cum[i - 1] || 1;
  const t = (a - cum[i - 1]) / segLen;
  const p0 = shape[i - 1];
  const p1 = shape[i];
  return [p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t];
}

/** Bearing in degrees (0 = north, clockwise) at `along`. */
export function headingAt(shape, cum, along) {
  const { i } = segmentAt(cum, along);
  const p0 = shape[i - 1];
  const p1 = shape[i];
  const lat = ((p0[1] + p1[1]) / 2) * (Math.PI / 180);
  const dx = (p1[0] - p0[0]) * metresPerDegreeLon(lat);
  const dy = (p1[1] - p0[1]) * M_PER_DEG_LAT;
  return ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360;
}

/** Project a [lon, lat] fix onto the shape: nearest point, travelled distance, off-shape error. */
export function projectOntoShape(shape, cum, lonlat) {
  let best = { along: 0, distance: Infinity };
  for (let i = 1; i < shape.length; i++) {
    const a = shape[i - 1];
    const b = shape[i];
    const lat = ((a[1] + b[1]) / 2) * (Math.PI / 180);
    const kx = metresPerDegreeLon(lat);
    const ax = (lonlat[0] - a[0]) * kx;
    const ay = (lonlat[1] - a[1]) * M_PER_DEG_LAT;
    const bx = (b[0] - a[0]) * kx;
    const by = (b[1] - a[1]) * M_PER_DEG_LAT;
    const len2 = bx * bx + by * by || 1;
    const t = Math.max(0, Math.min(1, (ax * bx + ay * by) / len2));
    const d = Math.hypot(ax - bx * t, ay - by * t);
    if (d < best.distance) best = { along: cum[i - 1] + t * distanceM(a, b), distance: d };
  }
  best.heading = headingAt(shape, cum, best.along);
  return best;
}

/** Fresh state for one vehicle at `along` metres, moving at `speedMps`. */
export function createVehicleState({ shape, along, speedMps, tripId = 1 }) {
  const { cum } = shapeLengths(shape);
  return {
    shape,
    cum,
    tripId,
    speedMps,
    drawnAlong: along,
    estimateAlong: along,
    heading: headingAt(shape, cum, along),
    lastFixT: null,
    blend: null,
    fadeStartT: null,
    appliedFixes: 0,
  };
}

/**
 * Apply a fix (DR-111 rules 1-4). `fix` is { lon, lat, speedMps?, tripId? }.
 * Returns one of 'blend' (fix ahead), 'hold' (fix behind), 'jump' (>150 m or new trip).
 */
export function applyFix(state, fix, now) {
  const projected = projectOntoShape(state.shape, state.cum, [fix.lon, fix.lat]);
  let event = 'blend';
  if (fix.tripId != null && fix.tripId !== state.tripId) {
    event = 'jump';
    state.tripId = fix.tripId;
  } else {
    const delta = projected.along - state.drawnAlong;
    if (Math.abs(delta) > RULES.jumpDistanceM) event = 'jump';
    else if (delta < 0) event = 'hold';
  }
  if (event === 'jump') {
    state.drawnAlong = projected.along;
    state.blend = null;
    state.fadeStartT = now;
  } else if (event === 'blend') {
    state.blend = { from: state.drawnAlong, startT: now, dur: RULES.blendSeconds };
  }
  state.estimateAlong = projected.along;
  if (fix.speedMps != null) state.speedMps = fix.speedMps;
  state.lastFixT = now;
  state.appliedFixes++;
  return event;
}

/**
 * Advance one frame (DR-111 rules 2-3): the estimate walks along the shape at speedMps, the
 * drawn position follows but never moves backwards, and a fresh ahead-fix is blended in over 1 s.
 */
export function advance(state, now, dt) {
  if (state.lastFixT != null && now - state.lastFixT <= RULES.maxEstimateAgeS) {
    state.estimateAlong += state.speedMps * dt;
  }
  let desired = state.estimateAlong;
  if (state.blend) {
    const t = state.blend.dur > 0 ? Math.min(1, (now - state.blend.startT) / state.blend.dur) : 1;
    desired = state.blend.from + (state.estimateAlong - state.blend.from) * t;
    if (t >= 1) state.blend = null;
  }
  if (desired > state.drawnAlong) state.drawnAlong = desired;
  state.heading = headingAt(state.shape, state.cum, state.drawnAlong);
  return state;
}

/** Fade alpha of a jump / trip change: 1 -> 0 -> 1 over fadeSeconds. */
export function alphaOf(state, now) {
  if (state.fadeStartT == null) return 1;
  const t = (now - state.fadeStartT) / RULES.fadeSeconds;
  if (t >= 1) {
    state.fadeStartT = null;
    return 1;
  }
  if (t <= 0) return 1;
  return t < 0.5 ? 1 - 2 * t : 2 * t - 1;
}

/** Current [lon, lat] of a vehicle. */
export function positionOf(state) {
  return pointAt(state.shape, state.cum, state.drawnAlong);
}
