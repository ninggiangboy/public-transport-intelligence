import { describe, expect, it } from 'vitest';

import { LabelAtlas } from './atlas';
import { ROLE, busMesh, trainMesh, type Mesh } from './mesh';

function bounds(mesh: Mesh): { min: number[]; max: number[] } {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  let axis = 0;
  for (const value of mesh.positions) {
    const low = min[axis] ?? Infinity;
    const high = max[axis] ?? -Infinity;
    min[axis] = Math.min(low, value);
    max[axis] = Math.max(high, value);
    axis = (axis + 1) % 3;
  }
  return { min, max };
}

function rolesOf(mesh: Mesh): Set<number> {
  const roles = new Set<number>();
  for (const role of mesh.roles) roles.add(role);
  return roles;
}

function expectBox(mesh: Mesh): void {
  const vertices = mesh.positions.length / 3;
  // A box mesh is 24 vertices and 36 indices per box: three indices for every two vertices.
  expect(mesh.positions.length % 3).toBe(0);
  expect(mesh.indices.length * 2).toBe(vertices * 3);
  expect(mesh.roles.length).toBe(vertices);
  for (const index of mesh.indices) expect(index).toBeLessThan(vertices);
  for (const value of mesh.positions) expect(Number.isFinite(value)).toBe(true);
}

describe('vehicle meshes', () => {
  it('builds a bus about 12 m long, pointing north, on the ground', () => {
    const mesh = busMesh();
    expectBox(mesh);
    const { min, max } = bounds(mesh);
    expect((max[1] ?? 0) - (min[1] ?? 0)).toBeCloseTo(12, 0);
    expect(min[2]).toBeCloseTo(0, 6);
    expect(min[0] ?? 0).toBeLessThan(0);
    expect(max[0] ?? 0).toBeGreaterThan(0);
  });

  it('gives the bus all of the bus-only roles', () => {
    const roles = rolesOf(busMesh());
    for (const role of [ROLE.BODY, ROLE.GLASS, ROLE.WHEEL, ROLE.HEADLIGHT, ROLE.TAILLIGHT, ROLE.DOOR]) {
      expect(roles.has(role)).toBe(true);
    }
  });

  it('builds a train of two cars joined by a connector', () => {
    const mesh = trainMesh();
    expectBox(mesh);
    const { min, max } = bounds(mesh);
    const length = (max[1] ?? 0) - (min[1] ?? 0);
    expect(length).toBeGreaterThan(30);
    expect(length).toBeLessThan(40);
    const roles = rolesOf(mesh);
    expect(roles.has(ROLE.BODY)).toBe(true);
    expect(roles.has(ROLE.WHEEL)).toBe(true);
    expect(roles.has(ROLE.CONNECTOR)).toBe(true);
  });

  it('covers every role across the two meshes', () => {
    const roles = rolesOf(busMesh());
    for (const role of rolesOf(trainMesh())) roles.add(role);
    expect([...roles].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6]);
  });
});

describe('LabelAtlas', () => {
  it('allocates a fresh cell per label and reuses it afterwards', () => {
    const atlas = new LabelAtlas({ draw: () => undefined });
    const first = atlas.cellOf('18');
    const second = atlas.cellOf('21');
    expect(first).toBe(0);
    expect(second).toBe(1);
    expect(atlas.cellOf('18')).toBe(first);
  });

  it('shares the overflow cell past capacity and paints every cell once', () => {
    const painted: number[] = [];
    const atlas = new LabelAtlas({
      draw: (_ctx, cell) => {
        painted.push(cell);
      },
    });
    for (let i = 0; i < atlas.capacity - 1; i++) {
      expect(atlas.cellOf(`route-${i}`)).toBe(i);
    }
    const overflow = atlas.capacity - 1;
    expect(atlas.cellOf('one-too-many')).toBe(overflow);
    expect(atlas.cellOf('another-too-many')).toBe(overflow);

    atlas.paint({} as CanvasRenderingContext2D);
    expect(painted).toEqual(Array.from({ length: atlas.capacity }, (_, cell) => cell));
  });
});
