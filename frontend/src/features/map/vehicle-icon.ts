// The vehicle icons of DOC-35 §6.2 as signed distance fields: one image per shape, tinted per feature with
// `icon-color` and outlined with `icon-halo-*`, instead of one image per colour.

export const ARROW_ICON = 'pti-vehicle-arrow';
export const DOT_ICON = 'pti-vehicle-dot';

/** Pixels per CSS pixel of the images; MapLibre scales them back with `pixelRatio`. */
const RATIO = 2;
const SIZE = 40 * RATIO;
/** Distance covered by the field on each side of the edge (TinySDF's `radius`). */
const RADIUS = 8 * RATIO;
/** The edge sits at alpha 0.75, as MapLibre expects (TinySDF's `cutoff` of 0.25). */
const CUTOFF = 0.25;

/**
 * A drop with its point to the north: a circle and a tip, rotated by `icon-rotate` with the bearing. The circle sits
 * in the middle of the image, so that it is centred on the vehicle's position whatever the bearing.
 */
function arrowShape(context: CanvasRenderingContext2D) {
  const r = 9 * RATIO;
  const cx = SIZE / 2;
  const cy = SIZE / 2;
  const tip = cy - r * 1.75;
  // The two sides of the tip touch the circle where they are tangent to it.
  const tangent = Math.acos(r / (cy - tip));
  context.beginPath();
  context.moveTo(cx, tip);
  context.arc(cx, cy, r, -Math.PI / 2 + tangent, (3 * Math.PI) / 2 - tangent);
  context.closePath();
  context.fill();
}

function dotShape(context: CanvasRenderingContext2D) {
  context.beginPath();
  context.arc(SIZE / 2, SIZE / 2, 9 * RATIO, 0, Math.PI * 2);
  context.fill();
}

/** Brute-force signed distance transform of a small mask: positive outside the shape, negative inside. */
function toSdf(mask: Uint8ClampedArray): Uint8ClampedArray {
  const inside = (index: number) => (mask[index * 4 + 3] ?? 0) >= 128;
  const edges: [number, number][] = [];
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      const self = inside(y * SIZE + x);
      const neighbours = [
        [x + 1, y],
        [x - 1, y],
        [x, y + 1],
        [x, y - 1],
      ];
      if (
        neighbours.some(
          ([nx = 0, ny = 0]) => nx >= 0 && ny >= 0 && nx < SIZE && ny < SIZE && inside(ny * SIZE + nx) !== self,
        )
      ) {
        edges.push([x, y]);
      }
    }
  }
  const out = new Uint8ClampedArray(SIZE * SIZE * 4);
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let best = Number.POSITIVE_INFINITY;
      for (const [ex, ey] of edges) best = Math.min(best, (ex - x) ** 2 + (ey - y) ** 2);
      const distance = (inside(y * SIZE + x) ? -1 : 1) * (Math.sqrt(best) - 0.5);
      const alpha = 255 - 255 * (distance / RADIUS + CUTOFF);
      const index = (y * SIZE + x) * 4;
      out[index] = 255;
      out[index + 1] = 255;
      out[index + 2] = 255;
      out[index + 3] = alpha;
    }
  }
  return out;
}

function render(draw: (context: CanvasRenderingContext2D) => void) {
  const canvas = document.createElement('canvas');
  canvas.width = SIZE;
  canvas.height = SIZE;
  const context = canvas.getContext('2d');
  if (!context) return undefined;
  context.fillStyle = 'white';
  draw(context);
  const mask = context.getImageData(0, 0, SIZE, SIZE).data;
  return { width: SIZE, height: SIZE, data: toSdf(mask) };
}

let cache: { arrow: ReturnType<typeof render>; dot: ReturnType<typeof render> } | undefined;

/** The two icons, computed once per page. */
export function vehicleIcons() {
  cache ??= { arrow: render(arrowShape), dot: render(dotShape) };
  return { ...cache, pixelRatio: RATIO };
}
