// Pure projection maths for the 3D vehicle layer (DR-111). Nothing here touches WebGL or the DOM, so the same code
// runs in the layer and in the tests. MapLibre's WebGL2 "3d" projection works in web-mercator [0,1] x/y with a
// conformal z in mercator units, so a metre on the ground is scaled by `mercatorUnitsPerMetre`.

const EARTH_RADIUS_M = 6378137;
const WORLD_CIRCUMFERENCE_M = 2 * Math.PI * EARTH_RADIUS_M;
// MapLibre's world square is 512 * 2^zoom device-independent pixels wide.
const WORLD_TILE_PX = 512;

export interface ScreenPoint {
  /** CSS pixels from the top-left of the map canvas. */
  x: number;
  y: number;
  /** NDC depth (-1 near, 1 far): smaller is nearer to the camera. */
  depth: number;
  /** On screen and in front of the camera. */
  visible: boolean;
}

/** A projected vehicle, ready for {@link pickNearest}. */
export interface PickPoint {
  id: string;
  x: number;
  y: number;
  depth: number;
}

/** Web-mercator [0,1] x/y of a lon/lat; [0,0] is the north-west corner of the world. */
export function mercatorXY(lon: number, lat: number): [number, number] {
  const x = (lon + 180) / 360;
  const sin = Math.sin((lat * Math.PI) / 180);
  const y = 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
  return [x, y];
}

/** Mercator units per ground metre at `lat`: the factor that turns the metre-sized meshes into the layer's z. */
export function mercatorUnitsPerMetre(lat: number): number {
  return 1 / (WORLD_CIRCUMFERENCE_M * Math.cos((lat * Math.PI) / 180));
}

/** Ground metres covered by one CSS pixel at `zoom`/`lat`; used for the on-screen sizes. */
export function metresPerPixel(zoom: number, lat: number): number {
  return (WORLD_CIRCUMFERENCE_M * Math.cos((lat * Math.PI) / 180)) / (WORLD_TILE_PX * 2 ** zoom);
}

/**
 * Project a lon/lat (elevated by `altitudeMetres`) to the map canvas. `matrix` is a 4x4 column-major
 * mercator-to-clip matrix (`options.defaultProjectionData.mainMatrix`).
 */
export function projectToScreen(
  matrix: ArrayLike<number>,
  lon: number,
  lat: number,
  altitudeMetres: number,
  width: number,
  height: number,
): ScreenPoint {
  const [mx, my] = mercatorXY(lon, lat);
  const mz = altitudeMetres * mercatorUnitsPerMetre(lat);
  // noUncheckedIndexedAccess: read through a helper so the arithmetic stays on plain numbers.
  const at = (index: number): number => matrix[index] ?? 0;
  const clipX = at(0) * mx + at(4) * my + at(8) * mz + at(12);
  const clipY = at(1) * mx + at(5) * my + at(9) * mz + at(13);
  const clipZ = at(2) * mx + at(6) * my + at(10) * mz + at(14);
  const clipW = at(3) * mx + at(7) * my + at(11) * mz + at(15);
  const inv = clipW !== 0 ? 1 / clipW : 0;
  const ndcX = clipX * inv;
  const ndcY = clipY * inv;
  const x = (ndcX * 0.5 + 0.5) * width;
  const y = (1 - (ndcY * 0.5 + 0.5)) * height;
  const visible = clipW > 0 && x >= 0 && x <= width && y >= 0 && y <= height;
  return { x, y, depth: clipZ * inv, visible };
}

/** The point nearest the pointer within `radiusPx`; equal distances are broken towards the smaller depth. */
export function pickNearest(points: readonly PickPoint[], x: number, y: number, radiusPx: number): string | undefined {
  const radius2 = radiusPx * radiusPx;
  let bestId: string | undefined;
  let bestDist = radius2;
  let bestDepth = Infinity;
  for (const point of points) {
    const dx = point.x - x;
    const dy = point.y - y;
    const dist = dx * dx + dy * dy;
    if (dist > radius2) continue;
    if (dist < bestDist || (dist === bestDist && point.depth < bestDepth)) {
      bestId = point.id;
      bestDist = dist;
      bestDepth = point.depth;
    }
  }
  return bestId;
}
