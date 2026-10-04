import type { Feature, Geometry, Point as GeoPoint } from 'geojson';

import type { components } from '@/api/generated/schema';
import { mapCopy } from '@/i18n/map';
import type { Tone } from '@/components/tone';
import { delayClass, DELAY_CLASSES, type DelayClass } from '@/lib/delay';
import { toMillis } from '@/lib/time';

import type { ColourMode } from '@/features/map/search';

// Pure rules of the live map (DOC-36 screens/live-map, DOC-35 §6): what a vehicle looks like, what the legend counts,
// where the camera goes, and the trip progress of the vehicle panel.

type Schemas = components['schemas'];
export type LiveVehicle = Schemas['LiveVehicleResponse'];
export type RouteItem = Schemas['RouteItemResponse'];
export type RouteDetail = Schemas['RouteDetailResponse'];
export type Direction = Schemas['DirectionResponse'];
export type PatternStop = Schemas['PatternStopResponse'];
export type StopItem = Schemas['StopItemResponse'];

const copy = mapCopy.map;

// ---------------------------------------------------------------------------------------------------------------------
// Age (DOC-35 §6.2, DOC-33 §5.1)

/** Older than this against `businessNow`: drawn at 0.4 opacity with "Last seen …". */
export const FADE_AFTER_MS = 90_000;
/** Older than this: not drawn at all. */
export const HIDE_AFTER_MS = 5 * 60_000;

export function vehicleAgeMs(vehicle: Pick<LiveVehicle, 'eventTimestamp'>, now: number): number {
  return now - toMillis(vehicle.eventTimestamp);
}

export function isFaded(vehicle: Pick<LiveVehicle, 'eventTimestamp'>, now: number): boolean {
  return vehicleAgeMs(vehicle, now) > FADE_AFTER_MS;
}

/** The vehicles to draw: those seen in the last 5 minutes. */
export function visibleVehicles(items: readonly LiveVehicle[], now: number): LiveVehicle[] {
  return items.filter((vehicle) => !(vehicleAgeMs(vehicle, now) > HIDE_AFTER_MS));
}

// ---------------------------------------------------------------------------------------------------------------------
// Colour modes (screens/live-map §6 "Colour vehicles by", DOC-35 §6.2)

export type CrowdingClass = 'success' | 'teal' | 'warning' | 'danger' | 'unknown';

export const CROWDING_CLASSES: readonly CrowdingClass[] = ['success', 'teal', 'warning', 'danger', 'unknown'];

const CROWDING: Record<string, CrowdingClass | undefined> = {
  EMPTY: 'success',
  MANY_SEATS_AVAILABLE: 'success',
  FEW_SEATS_AVAILABLE: 'teal',
  STANDING_ROOM_ONLY: 'warning',
  CRUSHED_STANDING_ROOM_ONLY: 'danger',
  FULL: 'danger',
  NOT_ACCEPTING_PASSENGERS: 'danger',
  NOT_BOARDABLE: 'danger',
};

export function crowdingClass(occupancyStatus: string | undefined): CrowdingClass {
  return (occupancyStatus ? CROWDING[occupancyStatus] : undefined) ?? 'unknown';
}

/** The occupancy label of DOC-37 §3.2; `undefined` when there is none to show. */
export function occupancyLabel(occupancyStatus: string | undefined): string | undefined {
  return occupancyStatus ? copy.occupancy[occupancyStatus] : undefined;
}

/** The legend bucket of a vehicle in a mode: its delay class, its route, or its crowding class. */
export function colourKey(vehicle: LiveVehicle, mode: ColourMode): string {
  if (mode === 'route') return vehicle.routeId;
  if (mode === 'crowding') return crowdingClass(vehicle.occupancyStatus);
  return delayClass(vehicle.delaySeconds);
}

/** Concrete colours read from the tokens of the current theme; MapLibre cannot resolve CSS variables. */
export interface Palette {
  delay: Record<DelayClass, string>;
  crowding: Record<CrowdingClass, string>;
  primary: string;
  bunching: string;
  foreground: string;
  card: string;
  land: string;
}

/** The badge tone of a delay class (DOC-35 §3.2, §3.4). */
export const DELAY_TONE: Record<DelayClass, Tone> = {
  early: 'info',
  'on-time': 'success',
  late: 'warning',
  'very-late': 'danger',
  unknown: 'neutral',
};

export function routeColorOf(route: Pick<RouteItem, 'color'> | undefined, fallback: string): string {
  return route?.color && /^[0-9a-fA-F]{6}$/.test(route.color) ? `#${route.color}` : fallback;
}

/** The colour a vehicle is drawn in. */
export function vehicleColour(
  vehicle: LiveVehicle,
  mode: ColourMode,
  palette: Palette,
  routes: ReadonlyMap<string, RouteItem>,
): string {
  if (mode === 'route') return routeColorOf(routes.get(vehicle.routeId), palette.primary);
  if (mode === 'crowding') return palette.crowding[crowdingClass(vehicle.occupancyStatus)];
  return palette.delay[delayClass(vehicle.delaySeconds)];
}

export function routeName(routeId: string, routes: ReadonlyMap<string, RouteItem>): string {
  return routes.get(routeId)?.displayName ?? routeId;
}

// ---------------------------------------------------------------------------------------------------------------------
// Legend (DOC-35 §6.3): one row per bucket of the mode, with the vehicles drawn in it right now.

export interface LegendRow {
  key: string;
  label: string;
  count: number;
  /** A CSS colour: a token for delay and crowding, the route colour for routes. */
  colour: string;
}

const DELAY_TOKEN: Record<DelayClass, string> = {
  early: 'var(--delay-early)',
  'on-time': 'var(--delay-on-time)',
  late: 'var(--delay-late)',
  'very-late': 'var(--delay-very-late)',
  unknown: 'var(--delay-unknown)',
};

/** Swatches of the overlay rows of the legend. */
export const OVERLAY_TOKEN = {
  bunching: 'var(--bunching)',
  disruption: 'var(--tone-danger-solid)',
  stale: 'var(--delay-unknown)',
} as const;

const CROWDING_TOKEN: Record<CrowdingClass, string> = {
  success: 'var(--tone-success-solid)',
  teal: 'var(--tone-teal-solid)',
  warning: 'var(--tone-warning-solid)',
  danger: 'var(--tone-danger-solid)',
  unknown: 'var(--delay-unknown)',
};

/** The crowding classes by the first occupancy label that falls in them. */
const CROWDING_LABEL: Record<CrowdingClass, string> = {
  success: copy.occupancy.MANY_SEATS_AVAILABLE ?? '',
  teal: copy.occupancy.FEW_SEATS_AVAILABLE ?? '',
  warning: copy.occupancy.STANDING_ROOM_ONLY ?? '',
  danger: copy.occupancy.FULL ?? '',
  unknown: copy.legend.noOccupancy,
};

function countBy(vehicles: readonly LiveVehicle[], key: (vehicle: LiveVehicle) => string): Map<string, number> {
  const counts = new Map<string, number>();
  for (const vehicle of vehicles) counts.set(key(vehicle), (counts.get(key(vehicle)) ?? 0) + 1);
  return counts;
}

export function legendRows(
  vehicles: readonly LiveVehicle[],
  mode: ColourMode,
  routes: ReadonlyMap<string, RouteItem>,
): LegendRow[] {
  const counts = countBy(vehicles, (vehicle) => colourKey(vehicle, mode));
  if (mode === 'delay') {
    return DELAY_CLASSES.map((key) => ({
      key,
      label: copy.legend.delay[key],
      count: counts.get(key) ?? 0,
      colour: DELAY_TOKEN[key],
    }));
  }
  if (mode === 'crowding') {
    return CROWDING_CLASSES.map((key) => ({
      key,
      label: CROWDING_LABEL[key],
      count: counts.get(key) ?? 0,
      colour: CROWDING_TOKEN[key],
    }));
  }
  return [...counts.entries()]
    .map(([routeId, count]) => ({
      key: routeId,
      label: routeName(routeId, routes),
      count,
      colour: routeColorOf(routes.get(routeId), 'var(--primary)'),
    }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'en-US', { numeric: true }));
}

/** Bunching pairs (by episode), and vehicles last seen over 90 s ago. */
export function overlayCounts(vehicles: readonly LiveVehicle[], now: number) {
  const episodes = new Set(vehicles.flatMap((vehicle) => (vehicle.bunching ? [vehicle.bunching.episodeId] : [])));
  return { bunching: episodes.size, stale: vehicles.filter((vehicle) => isFaded(vehicle, now)).length };
}

// ---------------------------------------------------------------------------------------------------------------------
// GeoJSON for MapLibre (DOC-35 §6.2)

export interface VehicleProperties {
  id: string;
  label: string;
  colour: string;
  opacity: number;
  /** -1 without a bearing: drawn as a dot. */
  bearing: number;
  veryLate: boolean;
}

export interface SceneOptions {
  mode: ColourMode;
  palette: Palette;
  routes: ReadonlyMap<string, RouteItem>;
  now: number;
  /** While a bunching pair is selected, every other vehicle is drawn at half opacity (DOC-35 §6.2). */
  focus?: ReadonlySet<string>;
}

export function vehicleFeatures(vehicles: readonly LiveVehicle[], options: SceneOptions) {
  const features: Feature<GeoPoint, VehicleProperties>[] = vehicles.map((vehicle) => {
    const faded = isFaded(vehicle, options.now);
    const dimmed = options.focus !== undefined && !options.focus.has(vehicle.vehicleId);
    const properties: VehicleProperties = {
      id: vehicle.vehicleId,
      label: routeName(vehicle.routeId, options.routes),
      colour: vehicleColour(vehicle, options.mode, options.palette, options.routes),
      opacity: faded ? 0.4 : dimmed ? 0.5 : 1,
      bearing: vehicle.bearing ?? -1,
      veryLate: options.mode === 'delay' && delayClass(vehicle.delaySeconds) === 'very-late',
    };
    return {
      type: 'Feature',
      id: vehicle.vehicleId,
      geometry: { type: 'Point', coordinates: [vehicle.lon, vehicle.lat] },
      properties,
    };
  });
  return { type: 'FeatureCollection' as const, features };
}

export interface BunchingProperties {
  kind: 'halo' | 'link';
  episodeId: string;
  id?: string;
}

/** Halos around both buses of every open pair, and a dashed link between them (viewer data only). */
export function bunchingFeatures(vehicles: readonly LiveVehicle[]) {
  const byId = new Map(vehicles.map((vehicle) => [vehicle.vehicleId, vehicle]));
  const features: Feature<Geometry, BunchingProperties>[] = [];
  for (const vehicle of vehicles) {
    const overlay = vehicle.bunching;
    if (!overlay) continue;
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [vehicle.lon, vehicle.lat] },
      properties: { kind: 'halo', id: vehicle.vehicleId, episodeId: overlay.episodeId },
    });
    const partner = byId.get(overlay.partnerVehicleId);
    if (partner && overlay.role === 'LEADER') {
      features.push({
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [vehicle.lon, vehicle.lat],
            [partner.lon, partner.lat],
          ],
        },
        properties: { kind: 'link', episodeId: overlay.episodeId },
      });
    }
  }
  return { type: 'FeatureCollection' as const, features };
}

export function routeFeatures(details: readonly RouteDetail[], fallback: string) {
  const features: Feature[] = details.flatMap((route) =>
    route.directions.map((direction) => ({
      type: 'Feature' as const,
      geometry: { type: 'LineString' as const, coordinates: direction.geometry.coordinates },
      properties: { routeId: route.routeId, colour: routeColorOf(route, fallback) },
    })),
  );
  return { type: 'FeatureCollection' as const, features };
}

export function pointFeatures<T extends { lat: number; lon: number }>(
  items: readonly T[],
  properties: (item: T) => Record<string, unknown>,
) {
  const features: Feature<GeoPoint>[] = items.map((item) => ({
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [item.lon, item.lat] },
    properties: properties(item),
  }));
  return { type: 'FeatureCollection' as const, features };
}

// ---------------------------------------------------------------------------------------------------------------------
// Geometry

/** The service area of the feed (DR-01, S-05) and the default camera (DOC-35 §6.1). */
export const FEED_BOUNDS: [number, number, number, number] = [-93.73, 44.707, -92.806, 45.33];
/** The feed area grown by 10 % on every side: the camera cannot leave it. */
export const MAX_BOUNDS: [number, number, number, number] = (() => {
  const [w, s, e, n] = FEED_BOUNDS;
  const dx = (e - w) * 0.1;
  const dy = (n - s) * 0.1;
  return [w - dx, s - dy, e + dx, n + dy];
})();

export type Bounds = [number, number, number, number];

/** [west, south, east, north] around every point; `undefined` for none. */
export function boundsOf(points: readonly (readonly number[])[]): Bounds | undefined {
  let bounds: Bounds | undefined;
  for (const [lon, lat] of points) {
    if (lon === undefined || lat === undefined) continue;
    bounds = bounds
      ? [Math.min(bounds[0], lon), Math.min(bounds[1], lat), Math.max(bounds[2], lon), Math.max(bounds[3], lat)]
      : [lon, lat, lon, lat];
  }
  return bounds;
}

export function routeBounds(details: readonly RouteDetail[]): Bounds | undefined {
  return boundsOf(details.flatMap((route) => route.directions.flatMap((direction) => direction.geometry.coordinates)));
}

const EARTH_RADIUS_M = 6_371_000;
const toRadians = (degrees: number) => (degrees * Math.PI) / 180;

/** Great-circle distance in metres. */
export function distanceMeters(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const dLat = toRadians(b.lat - a.lat);
  const dLon = toRadians(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRadians(a.lat)) * Math.cos(toRadians(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
}

/** "minLon,minLat,maxLon,maxLat" of a square `meters` on each side of the point, for E-06 `bbox`. */
export function bboxAround(lat: number, lon: number, meters: number): string {
  const dLat = meters / 111_320;
  const dLon = meters / (111_320 * Math.cos(toRadians(lat)));
  return [lon - dLon, lat - dLat, lon + dLon, lat + dLat].map((value) => value.toFixed(5)).join(',');
}

// ---------------------------------------------------------------------------------------------------------------------
// Panels

/** "Northbound" for `NB`, the label itself otherwise, "Direction 1" without one (DOC-37 §3.2). */
export function directionName(direction: Pick<Direction, 'label' | 'directionId'> | undefined, directionId: number) {
  const label = direction?.label;
  if (!label) return copy.vehicle.direction(directionId);
  return copy.directionLabel[label] ?? label;
}

/** "Nicollet Ave to Downtown" when both parts are known. */
export function vehicleHeadline(
  vehicle: Pick<LiveVehicle, 'headsign' | 'routeId'>,
  route: Pick<RouteItem, 'longName' | 'displayName'> | undefined,
): string {
  const longName = route?.longName;
  if (longName && vehicle.headsign) return copy.vehicle.headsign(longName, vehicle.headsign);
  return vehicle.headsign ?? longName ?? route?.displayName ?? vehicle.routeId;
}

/** The ordered stops of a direction, by `stopSequence`. */
export function orderedStops(direction: Direction | undefined): PatternStop[] {
  return direction ? [...direction.stops].sort((a, b) => a.stopSequence - b.stopSequence) : [];
}

export interface ProgressStop {
  stop: PatternStop;
  state: 'passed' | 'current' | 'upcoming';
  /** The next stop: the one with "Arriving …". */
  next: boolean;
  terminus: boolean;
}

export interface TripProgress {
  stops: ProgressStop[];
  /** The marker "Bus … · now" goes right after this stop. */
  markerAfter?: string;
}

/**
 * One stop behind, the current or next stop, and up to four after it (screens/live-map §4.1). The vehicle sits at its
 * stop when `STOPPED_AT`, and between the previous stop and the next one otherwise.
 */
export function tripProgress(
  vehicle: Pick<LiveVehicle, 'currentStopSequence' | 'stopId' | 'currentStatus'>,
  stops: readonly PatternStop[],
): TripProgress {
  let index = stops.findIndex((stop) => stop.stopSequence === vehicle.currentStopSequence);
  if (index < 0) index = stops.findIndex((stop) => stop.stopId === vehicle.stopId);
  if (index < 0) return { stops: [] };
  const stopped = vehicle.currentStatus === 'STOPPED_AT';
  const last = stops.length - 1;
  const window = stops.slice(Math.max(0, index - 1), Math.min(last, index + 4) + 1);
  const progress = window.map((stop): ProgressStop => {
    const position = stops.indexOf(stop);
    return {
      stop,
      state: position < index ? 'passed' : position === index && stopped ? 'current' : 'upcoming',
      next: position === index,
      terminus: position === last,
    };
  });
  const previous = stops[index - 1];
  const markerAfter = stopped ? stops[index]?.stopId : previous?.stopId;
  return { stops: progress, ...(markerAfter ? { markerAfter } : {}) };
}

/** Up to `pad` stops either side of the run of `ids` in a direction, for the horizontal strips. */
export function stopsAround(stops: readonly PatternStop[], ids: readonly string[], pad: number): PatternStop[] {
  const indexes = stops.flatMap((stop, index) => (ids.includes(stop.stopId) ? [index] : []));
  if (indexes.length === 0) return [];
  const from = Math.max(0, Math.min(...indexes) - pad);
  const to = Math.min(stops.length - 1, Math.max(...indexes) + pad);
  return stops.slice(from, to + 1);
}

/** Vehicles of the list view, grouped by route in the order of E-01. */
export function groupByRoute(
  vehicles: readonly LiveVehicle[],
  routes: readonly RouteItem[],
): { routeId: string; vehicles: LiveVehicle[] }[] {
  const order = new Map(routes.map((route, index) => [route.routeId, route.sortOrder ?? index]));
  const groups = new Map<string, LiveVehicle[]>();
  for (const vehicle of vehicles) {
    const group = groups.get(vehicle.routeId);
    if (group) group.push(vehicle);
    else groups.set(vehicle.routeId, [vehicle]);
  }
  return [...groups.entries()]
    .sort(
      ([a], [b]) =>
        (order.get(a) ?? Number.MAX_SAFE_INTEGER) - (order.get(b) ?? Number.MAX_SAFE_INTEGER) ||
        a.localeCompare(b, 'en-US', { numeric: true }),
    )
    .map(([routeId, items]) => ({
      routeId,
      vehicles: items.sort((a, b) =>
        (a.label ?? a.vehicleId).localeCompare(b.label ?? b.vehicleId, 'en-US', { numeric: true }),
      ),
    }));
}
