import { z } from 'zod/mini';

// Search params of /map (DOC-34 §5.2, screens/live-map §2). Bad values are dropped, so an old link still opens; the
// defaults (`view=map`, `colour=delay`, every route) are never written.

export const MAX_ROUTES = 20;
export const COLOUR_MODES = ['delay', 'route', 'crowding'] as const;
export const VIEWS = ['map', 'list'] as const;

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

/** `c` is "lon,lat,zoom"; the URL parser hands it over split at the commas. */
const camera = z.pipe(
  z.transform((value: unknown) => {
    const parts = (Array.isArray(value) ? value : String(value).split(',')).map(Number);
    const [lon, lat, zoom] = parts;
    if (parts.length !== 3 || lon === undefined || lat === undefined || zoom === undefined) return undefined;
    if (!(Math.abs(lon) <= 180 && Math.abs(lat) <= 90 && zoom >= 0 && zoom <= 24)) return undefined;
    return formatCamera({ lon, lat, zoom });
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
}

/** "−93.2650,44.9778,12.5000" with 4 decimals (DOC-34 §5.2). */
export function formatCamera({ lon, lat, zoom }: Camera): string {
  return [lon, lat, zoom].map((value) => value.toFixed(4)).join(',');
}

export function parseCamera(c: string | undefined): Camera | undefined {
  if (!c) return undefined;
  const [lon, lat, zoom] = c.split(',').map(Number);
  return lon === undefined || lat === undefined || zoom === undefined ? undefined : { lon, lat, zoom };
}
