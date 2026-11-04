// Box-only meshes for the 3D vehicles (DR-111). Each mesh is in metres, points to +Y (north, before the shader
// rotates it by the vehicle heading) and sits on the ground (z = 0). A per-vertex role lets the fragment shader pick
// its fixed palette; only the body (role 0) is tinted by the instance colour.

/** Role of a vertex, as read by the shader's palette. */
export const ROLE = {
  BODY: 0,
  GLASS: 1,
  WHEEL: 2,
  HEADLIGHT: 3,
  TAILLIGHT: 4,
  DOOR: 5,
  CONNECTOR: 6,
} as const;

export interface Mesh {
  /** xyz triples in metres, three per triangle vertex. */
  positions: Float32Array;
  /** One role per vertex; values are the {@link ROLE} constants. */
  roles: Uint8Array;
  /** Triangle indices into the vertices. */
  indices: Uint16Array;
}

type Vec3 = readonly [number, number, number];

// The six faces of a unit box, each as four corners in [-1,1]; the winding does not matter because culling is off.
const FACES: readonly (readonly Vec3[])[] = [
  [
    [-1, -1, 1],
    [1, -1, 1],
    [1, 1, 1],
    [-1, 1, 1],
  ],
  [
    [-1, -1, -1],
    [-1, 1, -1],
    [1, 1, -1],
    [1, -1, -1],
  ],
  [
    [1, -1, -1],
    [1, 1, -1],
    [1, 1, 1],
    [1, -1, 1],
  ],
  [
    [-1, -1, -1],
    [-1, -1, 1],
    [-1, 1, 1],
    [-1, 1, -1],
  ],
  [
    [-1, 1, -1],
    [-1, 1, 1],
    [1, 1, 1],
    [1, 1, -1],
  ],
  [
    [-1, -1, -1],
    [1, -1, -1],
    [1, -1, 1],
    [-1, -1, 1],
  ],
];

class MeshBuilder {
  private readonly positions: number[] = [];
  private readonly roles: number[] = [];
  private readonly indices: number[] = [];

  /** Append an axis-aligned box (24 vertices, 36 indices) with every vertex carrying `role`. */
  box(center: Vec3, size: Vec3, role: number): void {
    const base = this.positions.length / 3;
    const hx = size[0] / 2;
    const hy = size[1] / 2;
    const hz = size[2] / 2;
    for (const face of FACES) {
      for (const corner of face) {
        this.positions.push(center[0] + corner[0] * hx, center[1] + corner[1] * hy, center[2] + corner[2] * hz);
        this.roles.push(role);
      }
    }
    for (let face = 0; face < FACES.length; face++) {
      const o = base + face * 4;
      this.indices.push(o, o + 1, o + 2, o, o + 2, o + 3);
    }
  }

  build(): Mesh {
    return {
      positions: new Float32Array(this.positions),
      roles: new Uint8Array(this.roles),
      indices: new Uint16Array(this.indices),
    };
  }
}

const BUS_LENGTH = 12;
const BUS_WIDTH = 2.5;
const BUS_HEIGHT = 3;

/** A bus: body, a dark glass band on each side, four wheels, head/tail lights and a door on the right. */
export function busMesh(): Mesh {
  const b = new MeshBuilder();
  b.box([0, 0, BUS_HEIGHT / 2], [BUS_WIDTH, BUS_LENGTH, BUS_HEIGHT], ROLE.BODY);
  for (const side of [1, -1]) {
    b.box([side * (BUS_WIDTH / 2 + 0.03), 0, 2.1], [0.08, 10, 1], ROLE.GLASS);
  }
  for (const side of [1, -1]) {
    for (const end of [1, -1]) {
      b.box([side * (BUS_WIDTH / 2 + 0.04), end * 4, 0.5], [0.42, 1.1, 1], ROLE.WHEEL);
    }
  }
  for (const side of [0.8, -0.8]) {
    b.box([side, BUS_LENGTH / 2, 0.8], [0.5, 0.14, 0.3], ROLE.HEADLIGHT);
    b.box([side, -BUS_LENGTH / 2, 0.8], [0.5, 0.14, 0.3], ROLE.TAILLIGHT);
  }
  // The door is on the right (+X) side, towards the front (+Y).
  b.box([BUS_WIDTH / 2 + 0.05, 2, 1], [0.12, 1.3, 2], ROLE.DOOR);
  return b.build();
}

const CAR_LENGTH = 16;
const CAR_WIDTH = 2.6;
const CAR_HEIGHT = 3.2;
const CAR_GAP = 1;

/** A train: two cars joined by a short soft connector, each with a small pantograph frame on the roof. */
export function trainMesh(): Mesh {
  const b = new MeshBuilder();
  const offset = CAR_LENGTH / 2 + CAR_GAP / 2;
  b.box([0, offset, CAR_HEIGHT / 2], [CAR_WIDTH, CAR_LENGTH, CAR_HEIGHT], ROLE.BODY);
  b.box([0, -offset, CAR_HEIGHT / 2], [CAR_WIDTH, CAR_LENGTH, CAR_HEIGHT], ROLE.BODY);
  b.box([0, 0, CAR_HEIGHT / 2], [CAR_WIDTH * 0.7, CAR_GAP + 0.4, CAR_HEIGHT * 0.75], ROLE.CONNECTOR);
  for (const car of [offset, -offset]) {
    for (const side of [0.45, -0.45]) {
      b.box([side, car, CAR_HEIGHT + 0.25], [0.08, 0.08, 0.5], ROLE.WHEEL);
    }
    b.box([0, car, CAR_HEIGHT + 0.5], [1.1, 0.08, 0.08], ROLE.WHEEL);
  }
  return b.build();
}
