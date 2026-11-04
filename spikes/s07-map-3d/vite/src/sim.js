// Simulated fleet for the S-07 spike. ~40 random-walk routes around downtown Minneapolis,
// 15 vehicles per route, each emitting a "projected onto the shape" fix every 10-30 s.
import { shapeLengths, pointAt } from './motion.js';

export const DOWNTOWN = { lon: -93.265, lat: 44.978 };
export const BBOX = [-93.3, 44.94, -93.22, 45.0];

const PALETTE = [
  [230, 60, 60], [60, 170, 90], [60, 120, 230], [240, 160, 40], [165, 80, 220],
  [40, 190, 190], [235, 95, 160], [150, 150, 60], [90, 90, 235], [245, 200, 60],
];

function seedRng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

/** Random-walk polylines around the downtown centre. */
export function makeRoutes(count = 40, seed = 7) {
  const rand = seedRng(seed);
  const routes = [];
  for (let i = 0; i < count; i++) {
    let lon = DOWNTOWN.lon + (rand() - 0.5) * 0.018;
    let lat = DOWNTOWN.lat + (rand() - 0.5) * 0.018;
    let bearing = rand() * Math.PI * 2;
    const shape = [[lon, lat]];
    const steps = 26 + Math.floor(rand() * 14);
    for (let s = 0; s < steps; s++) {
      bearing += (rand() - 0.5) * 0.9;
      const step = 80 + rand() * 140;
      lon += (Math.sin(bearing) * step) / (111320 * Math.cos((lat * Math.PI) / 180));
      lat += (Math.cos(bearing) * step) / 110540;
      shape.push([lon, lat]);
    }
    routes.push({ id: i, color: PALETTE[i % PALETTE.length], shape });
  }
  return routes;
}

/** 600 vehicles (15 per route) with a precomputed fix schedule for the first `horizonS` seconds. */
export function makeFleet(routes, perRoute = 15, options = {}) {
  const {
    seed = 11,
    speedMin = 4,
    speedMax = 14,
    fixMinS = 10,
    fixMaxS = 30,
    horizonS = 180,
    jitterM = 20,
  } = options;
  const rand = seedRng(seed);
  const vehicles = [];
  for (const route of routes) {
    const { cum, total } = shapeLengths(route.shape);
    for (let v = 0; v < perRoute; v++) {
      const speedMps = speedMin + rand() * (speedMax - speedMin);
      let along = rand() * total;
      const fixes = [];
      let t = 0;
      while (t <= horizonS) {
        const jitter = (rand() - 0.5) * 2 * jitterM;
        const p = pointAt(route.shape, cum, Math.min(total, Math.max(0, along + jitter)));
        fixes.push({ t, lon: p[0], lat: p[1], speedMps });
        const dt = fixMinS + rand() * (fixMaxS - fixMinS);
        t += dt;
        along = Math.min(total, along + speedMps * dt);
      }
      vehicles.push({
        id: vehicles.length,
        routeId: route.id,
        tripId: route.id + 1,
        color: route.color,
        shape: route.shape,
        along,
        speedMps,
        fixes,
      });
    }
  }
  return vehicles;
}
