import { describe, expect, it } from 'vitest';

import {
  MotionModel,
  distanceMetres,
  headingAt,
  indexShape,
  pointAt,
  projectOntoShape,
  type FixInput,
  type ShapeIndex,
  type Shapes,
} from './motion';

const FRAME_MS = 1000 / 60;
/** ~1.6 km straight east, the same kind of shape the spike check uses. */
const SHAPE = line([-93.3, 44.95], [-93.28, 44.95]);

function line(from: readonly [number, number], to: readonly [number, number], points = 40): ShapeIndex {
  const shape: [number, number][] = [];
  for (let i = 0; i <= points; i++) {
    const t = i / points;
    shape.push([from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t]);
  }
  return indexShape(shape);
}

function shapesOf(...list: ShapeIndex[]): Shapes {
  return { forRoute: () => list };
}

function fixAt(overrides: Partial<FixInput> = {}): FixInput {
  return {
    vehicleId: 'v1',
    tripId: 't1',
    routeId: '18',
    directionId: 0,
    lon: 0,
    lat: 0,
    currentStatus: 'IN_TRANSIT_TO',
    at: 0,
    speedMps: 0,
    ...overrides,
  };
}

function drawn(model: MotionModel, id = 'v1') {
  const vehicle = model.get(id);
  if (!vehicle) throw new Error(`no drawn vehicle ${id}`);
  return vehicle;
}

/** The travelled distance of the drawn position, since the drawn point sits on the shape. */
function alongOf(index: ShapeIndex, out: { lon: number; lat: number }): number {
  return projectOntoShape(index, [out.lon, out.lat]).along;
}

function runFrames(model: MotionModel, from: number, count: number): number {
  let now = from;
  for (let f = 0; f < count; f++) {
    now += FRAME_MS;
    model.frame(now);
  }
  return now;
}

describe('geometry helpers', () => {
  it('indexes a shape and answers pointAt / headingAt / projection', () => {
    const index = line([0, 0], [0.01, 0], 10);
    const total = distanceMetres([0, 0], [0.01, 0]);

    expect(pointAt(index, 0)).toEqual([0, 0]);
    expect(pointAt(index, total / 2)[0]).toBeCloseTo(0.005, 6);
    expect(headingAt(index, 10)).toBeCloseTo(90, 6);

    const onShape = projectOntoShape(index, [0.005, 0]);
    expect(onShape.along).toBeCloseTo(total / 2, 0);
    expect(onShape.distance).toBeLessThan(1);

    const offShape = projectOntoShape(index, [0.005, 0.0005]);
    expect(offShape.along).toBeCloseTo(total / 2, 0);
    expect(offShape.distance).toBeGreaterThan(50);
  });
});

describe('MotionModel', () => {
  it('never moves backwards after a fix behind the drawn point', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const start = pointAt(SHAPE, 500);
    model.update([fixAt({ lon: start[0], lat: start[1], speedMps: 10, at: 0 })], 0);
    model.frame(0);

    let now = 0;
    let previous = alongOf(SHAPE, drawn(model));
    let backwards = false;
    for (let f = 0; f < 600; f++) {
      now += FRAME_MS;
      if (f === 30) {
        // a fix 60 m behind arrives (jitter / stale GPS)
        const behind = pointAt(SHAPE, alongOf(SHAPE, drawn(model)) - 60);
        model.update([fixAt({ lon: behind[0], lat: behind[1], speedMps: 10, at: now })], now);
      }
      model.frame(now);
      const along = alongOf(SHAPE, drawn(model));
      if (along + 1e-6 < previous) backwards = true;
      previous = along;
    }
    expect(backwards).toBe(false);
  });

  it('stops estimating 20 s after the fix timestamp', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const start = pointAt(SHAPE, 100);
    model.update([fixAt({ lon: start[0], lat: start[1], speedMps: 10, at: 0 })], 0);
    model.frame(0);

    const atTwenty = runFrames(model, 0, 1200);
    const frozen = alongOf(SHAPE, drawn(model));
    runFrames(model, atTwenty, 900);
    const later = alongOf(SHAPE, drawn(model));

    expect(frozen).toBeGreaterThan(100); // it did move
    expect(Math.abs(later - frozen)).toBeLessThan(1e-6);
  });

  it('holds at a STOPPED_AT fix', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const start = pointAt(SHAPE, 300);
    model.update([fixAt({ lon: start[0], lat: start[1], speedMps: 10, currentStatus: 'STOPPED_AT', at: 0 })], 0);
    model.frame(0);

    runFrames(model, 0, 120);
    expect(alongOf(SHAPE, drawn(model))).toBeCloseTo(300, 6);
  });

  it('blends an ahead-fix in over 1 s, monotone and reaching the estimate', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const from = pointAt(SHAPE, 0);
    model.update([fixAt({ lon: from[0], lat: from[1], speedMps: 0, at: 0 })], 0);
    model.frame(0);

    const ahead = pointAt(SHAPE, 100);
    model.update([fixAt({ lon: ahead[0], lat: ahead[1], speedMps: 0, at: 1000 })], 1000);

    const samples: number[] = [];
    let now = 1000;
    for (let f = 0; f < 90; f++) {
      now += FRAME_MS;
      model.frame(now);
      samples.push(alongOf(SHAPE, drawn(model)));
    }

    let monotone = true;
    let previous = -Infinity;
    for (const value of samples) {
      if (value + 1e-9 < previous) monotone = false;
      previous = value;
    }
    expect(monotone).toBe(true);
    expect(samples.at(-1)).toBeCloseTo(100, 1);
  });

  it('fades a >150 m jump: alpha dips to 0 and back without sliding', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const from = pointAt(SHAPE, 0);
    model.update([fixAt({ lon: from[0], lat: from[1], speedMps: 0, at: 0 })], 0);
    model.frame(0);

    const jump = pointAt(SHAPE, 200);
    model.update([fixAt({ lon: jump[0], lat: jump[1], speedMps: 0, at: 1000 })], 1000);

    let minAlpha = 1;
    let slid = false;
    let now = 1000;
    for (let f = 0; f < 12; f++) {
      now += FRAME_MS;
      model.frame(now);
      const vehicle = drawn(model);
      minAlpha = Math.min(minAlpha, vehicle.alpha);
      const along = alongOf(SHAPE, vehicle);
      if (Math.abs(along - 0) > 1 && Math.abs(along - 200) > 1) slid = true;
    }

    expect(minAlpha).toBeLessThan(0.2);
    expect(slid).toBe(false);
    expect(drawn(model).alpha).toBe(1); // back to 1 within 200 ms
    expect(alongOf(SHAPE, drawn(model))).toBeCloseTo(200, 1);
  });

  it('fades on a tripId change', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const at = pointAt(SHAPE, 100);
    model.update([fixAt({ tripId: 't1', lon: at[0], lat: at[1], speedMps: 0, at: 0 })], 0);
    model.frame(0);

    model.update([fixAt({ tripId: 't2', lon: at[0], lat: at[1], speedMps: 0, at: 1000 })], 1000);

    let minAlpha = 1;
    let now = 1000;
    for (let f = 0; f < 12; f++) {
      now += FRAME_MS;
      model.frame(now);
      minAlpha = Math.min(minAlpha, drawn(model).alpha);
    }
    expect(minAlpha).toBeLessThan(0.2);
    expect(alongOf(SHAPE, drawn(model))).toBeCloseTo(100, 3);
  });

  it('with no shape, dead-reckons straight along the bearing', () => {
    const model = new MotionModel({ shapes: shapesOf() });
    model.update([fixAt({ lon: 0, lat: 0, bearing: 90, speedMps: 10, at: 0 })], 0);
    model.frame(0);

    runFrames(model, 0, 300);
    const vehicle = drawn(model);

    expect(vehicle.heading).toBeCloseTo(90, 3);
    expect(vehicle.lon).toBeGreaterThan(0);
    expect(vehicle.lat).toBeCloseTo(0, 6);
    const moved = distanceMetres([0, 0], [vehicle.lon, vehicle.lat]);
    expect(moved).toBeGreaterThan(45);
    expect(moved).toBeLessThan(55);
  });

  it('falls back to the bearing when the vehicle is >50 m off the shape', () => {
    const flat = line([-0.01, 0], [0.01, 0], 20);
    const model = new MotionModel({ shapes: shapesOf(flat) });
    model.update([fixAt({ lon: 0, lat: 0.002, bearing: 90, speedMps: 10, at: 0 })], 0);
    model.frame(0);

    runFrames(model, 0, 300);
    const vehicle = drawn(model);

    expect(vehicle.heading).toBeCloseTo(90, 3);
    expect(vehicle.lat).toBeCloseTo(0.002, 6); // stayed on the bearing line, not snapped to the shape
    expect(vehicle.lon).toBeGreaterThan(0);
  });

  it('takes the heading from the shape direction, not the GPS bearing', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const at = pointAt(SHAPE, 200);
    model.update([fixAt({ lon: at[0], lat: at[1], bearing: 180, speedMps: 0, at: 0 })], 0);
    model.frame(0);

    expect(drawn(model).heading).toBeCloseTo(90, 3);
  });

  it('in reduced motion draws the fix and never estimates', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE), reduced: true });
    model.update([fixAt({ lon: 5, lat: 6, speedMps: 10, at: 0 })], 0);
    model.frame(0);

    const now = runFrames(model, 0, 300);
    let vehicle = drawn(model);
    expect(vehicle.lon).toBe(5);
    expect(vehicle.lat).toBe(6);
    expect(vehicle.estimated).toBe(false);

    model.update([fixAt({ lon: 7, lat: 8, speedMps: 10, at: now + 1000 })], now + 1000);
    // The vehicle stays put while it fades out and only changes place in the middle of the 150 ms fade.
    model.frame(now + 1000 + 30);
    vehicle = drawn(model);
    expect(vehicle.lon).toBe(5);
    expect(vehicle.alpha).toBeLessThan(1);
    model.frame(now + 1000 + 90);
    vehicle = drawn(model);
    expect(vehicle.lon).toBe(7);
    expect(vehicle.lat).toBe(8);
    model.frame(now + 1000 + 200);
    expect(drawn(model).alpha).toBe(1);
  });

  it('drops vehicles absent from the update list', () => {
    const model = new MotionModel({ shapes: shapesOf() });
    model.update([fixAt({ vehicleId: 'v1' }), fixAt({ vehicleId: 'v2' })], 0);
    model.frame(0);

    expect(model.get('v1')).toBeDefined();
    expect(model.get('v2')).toBeDefined();

    model.update([fixAt({ vehicleId: 'v1', at: 1000 })], 1000);
    expect(model.get('v1')).toBeDefined();
    expect(model.get('v2')).toBeUndefined();
  });

  it('ignores a fix older than the one already applied', () => {
    const model = new MotionModel({ shapes: shapesOf(SHAPE) });
    const at100 = pointAt(SHAPE, 100);
    model.update([fixAt({ lon: at100[0], lat: at100[1], speedMps: 0, at: 1000 })], 1000);
    model.frame(1000);
    const before = drawn(model).lon;

    const at50 = pointAt(SHAPE, 50);
    model.update([fixAt({ lon: at50[0], lat: at50[1], speedMps: 0, at: 500 })], 1000);

    expect(drawn(model).lon).toBe(before);
  });
});
