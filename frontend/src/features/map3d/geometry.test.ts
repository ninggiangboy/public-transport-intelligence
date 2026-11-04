import { describe, expect, it } from 'vitest';

import {
  mercatorUnitsPerMetre,
  mercatorXY,
  metresPerPixel,
  pickNearest,
  projectToScreen,
  type PickPoint,
} from './geometry';

const IDENTITY = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
// A camera whose w row is negative puts every point behind the eye.
const BEHIND_CAMERA = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1];

describe('mercator maths', () => {
  it('maps the equator to the centre of the mercator square', () => {
    expect(mercatorXY(0, 0)).toEqual([0.5, 0.5]);
    const [x, y] = mercatorXY(0, 45);
    expect(x).toBeCloseTo(0.5, 6);
    expect(y).toBeLessThan(0.5);
  });

  it('scales a metre into mercator units, more units closer to the pole', () => {
    expect(mercatorUnitsPerMetre(0)).toBeCloseTo(1 / (2 * Math.PI * 6378137), 12);
    expect(mercatorUnitsPerMetre(60)).toBeGreaterThan(mercatorUnitsPerMetre(0));
  });

  it('halves the ground resolution per zoom level', () => {
    expect(metresPerPixel(0, 0)).toBeCloseTo(78271.5, 1);
    expect(metresPerPixel(1, 0)).toBeCloseTo(metresPerPixel(0, 0) / 2, 6);
  });
});

describe('projectToScreen', () => {
  it('projects a known point through an identity matrix', () => {
    const point = projectToScreen(IDENTITY, 0, 0, 0, 100, 50);
    expect(point.x).toBeCloseTo(75, 6);
    expect(point.y).toBeCloseTo(12.5, 6);
    expect(point.depth).toBeCloseTo(0, 6);
    expect(point.visible).toBe(true);
  });

  it('pushes a point up the depth axis with altitude', () => {
    const point = projectToScreen(IDENTITY, 0, 0, 100, 100, 100);
    expect(point.depth).toBeCloseTo(100 * mercatorUnitsPerMetre(0), 9);
    expect(point.depth).toBeGreaterThan(0);
  });

  it('hides a point behind the camera', () => {
    const point = projectToScreen(BEHIND_CAMERA, 0, 0, 0, 100, 100);
    expect(point.visible).toBe(false);
  });
});

describe('pickNearest', () => {
  const points: PickPoint[] = [
    { id: 'a', x: 0, y: 0, depth: 5 },
    { id: 'b', x: 3, y: 4, depth: 1 },
  ];

  it('returns the nearest point inside the radius, ignoring the rest', () => {
    expect(pickNearest(points, 0, 0, 10)).toBe('a');
    expect(pickNearest(points, 0, 0, 2)).toBe('a');
    expect(pickNearest(points, 3, 4, 1)).toBe('b');
    expect(pickNearest(points, 100, 100, 10)).toBeUndefined();
  });

  it('breaks equal distances towards the smaller depth', () => {
    const tie: PickPoint[] = [
      { id: 'near-later', x: 1, y: 0, depth: 10 },
      { id: 'far-earlier', x: -1, y: 0, depth: 2 },
    ];
    expect(pickNearest(tie, 0, 0, 5)).toBe('far-earlier');
  });
});
