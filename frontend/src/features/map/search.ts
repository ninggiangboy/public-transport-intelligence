import { z } from 'zod/mini';

// Search params of /map (DOC-34 §5.2, screens/live-map §2). Bad values are dropped, so an old link still opens; the
// defaults (`view=map`, `colour=delay`, every route) are never written.

export const MAX_ROUTES = 20;
export const COLOUR_MODES = ['delay', 'route', 'crowding'] as const;
export const VIEWS = ['map', '3d', 'list'] as const;

export type ColourMode = (typeof COLOUR_MODES)[number];
export type MapView = (typeof VIEWS)[number];

const id = z.string().check(z.minLength(1));

const routeList = z.pipe(
  z.transform((value: unknown) => {
    const items = typeof value === 'string' || typeof value === 'number' ? [value] : value;
    return Array.isArray(items) ? [...new Set(items.map(String))].slice(0, MAX_ROUTES) : items;
  }),
  z.array(id),
);

/** `c` is "lon,lat,zoom" or, from the 3D view, "lon,lat,zoom,bearing,pitch" (DR-111); the URL parser hands it over split at the commas. */
const camera = z.pipe(
  z.transform((value: unknown) => {
    const parts = (Array.isArray(value) ? value : String(value).split(',')).map(Number);
    const [lon, lat, zoom, bearing, pitch] = parts;
    if ((parts.length !== 3 && parts.length !== 5) || lon === undefined || lat === undefined || zoom === undefined) {
      return undefined;
    }
    if (!(Math.abs(lon) <= 180 && Math.abs(lat) <= 90 && zoom >= 0 && zoom <= 24)) return undefined;
    if (parts.length === 3) return formatCamera({ lon, lat, zoom });
    if (typeof bearing !== 'number' || typeof pitch !== 'number') return undefined;
    if (!(Math.abs(bearing) <= 360 && pitch >= 0 && pitch <= 85)) return undefined;
    return formatCamera({ lon, lat, zoom, bearing, pitch });
  }),
  z.string(),
);

export const mapSearch = z.object({
  route: z.catch(z.optional(routeList), undefined),
  vehicle: z.catch(z.optional(id), undefined),
  bunching: z.catch(z.optional(id), undefined),
  disruption: z.catch(z.optional(id), undefined),
  c: z.catch(z.optional(camera), undefined),
  view: z.catch(z.optional(z.enum(VIEWS)), undefined),
  colour: z.catch(z.optional(z.enum(COLOUR_MODES)), undefined),
});

export type MapSearch = z.infer<typeof mapSearch>;

export interface Camera {
  lon: number;
  lat: number;
  zoom: number;
  /** Degrees clockwise from north; only the 3D view writes it. */
  bearing?: number | undefined;
  /** Degrees from straight down; only the 3D view writes it. */
  pitch?: number | undefined;
}

/**
 * "−93.2650,44.9778,12.5000" with 4 decimals (DOC-34 §5.2). With a `bearing` and a `pitch` (3D view, DR-111) two
 * whole degrees are appended; the 2D view never passes them.
 */
export function formatCamera({ lon, lat, zoom, bearing, pitch }: Camera): string {
  const base = [lon, lat, zoom].map((value) => value.toFixed(4)).join(',');
  if (bearing === undefined || pitch === undefined) return base;
  return `${base},${Math.round(bearing)},${Math.round(pitch)}`;
}

export function parseCamera(c: string | undefined): Camera | undefined {
  if (!c) return undefined;
  const [lon, lat, zoom, bearing, pitch] = c.split(',').map(Number);
  if (lon === undefined || lat === undefined || zoom === undefined) return undefined;
  return bearing === undefined || pitch === undefined ? { lon, lat, zoom } : { lon, lat, zoom, bearing, pitch };
}
