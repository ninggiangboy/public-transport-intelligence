import type { components } from '@/api/generated/schema';
import { en } from '@/i18n/en';
import { formatTime, toMillis } from '@/lib/time';

export type Arrival = components['schemas']['ArrivalResponse'];
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

/** A trip whose predicted time is more than this far in the past is hidden until the next refetch (§5). */
const PAST_GRACE_MS = 60_000;

export function confidenceOf(arrival: Arrival): Confidence {
  const level = arrival.confidence;
  return level === 'HIGH' || level === 'MEDIUM' || level === 'LOW' ? level : 'NONE';
}

/** The time a passenger should expect: the real-time prediction when there is one (F-ANL-06). */
export function expectedAt(arrival: Arrival): string {
  return arrival.realtimeArrival ?? arrival.predictedArrival;
}

export interface ArrivalFilter {
  routes?: readonly string[];
  dir?: number;
}

/** Upcoming trips at `now` (businessNow), filtered on the client by route and direction (§6). */
export function visibleArrivals(items: readonly Arrival[], now: number, filter: ArrivalFilter = {}): Arrival[] {
  return items.filter(
    (arrival) =>
      toMillis(expectedAt(arrival)) >= now - PAST_GRACE_MS &&
      (!filter.routes?.length || filter.routes.includes(arrival.routeId)) &&
      (filter.dir === undefined || arrival.directionId === filter.dir),
  );
}

/** The directions the trips at this stop run in, in order. */
export function directionsOf(items: readonly Arrival[]): number[] {
  return [...new Set(items.map((arrival) => arrival.directionId))].sort();
}

/** The direction most trips of `routeId` take at this stop; `undefined` when none is listed. */
export function routeDirection(items: readonly Arrival[], routeId: string): number | undefined {
  const counts = new Map<number, number>();
  for (const arrival of items) {
    if (arrival.routeId === routeId) counts.set(arrival.directionId, (counts.get(arrival.directionId) ?? 0) + 1);
  }
  let best: number | undefined;
  for (const [direction, count] of counts) {
    if (best === undefined || count > (counts.get(best) ?? 0)) best = direction;
  }
  return best;
}

/** The big ETA of a row (DOC-37 §4.4): "Due", "{n}" + "min", or the clock time from 60 min on. */
export type EtaDisplay =
  { kind: 'due' } | { kind: 'minutes'; minutes: number } | { kind: 'time'; time: string; unit: string };

export function etaDisplay(at: string, now: number, timeZone: string, schedule = false): EtaDisplay {
  const remaining = (toMillis(at) - now) / 1000;
  if (!schedule && remaining <= 60) return { kind: 'due' };
  if (!schedule && remaining < 3600) return { kind: 'minutes', minutes: Math.floor(remaining / 60) };
  // "4:49 PM" → "4:49" + "PM", the unit set small like "min".
  const text = formatTime(at, { timeZone, showZone: false });
  const [time = text, unit = ''] = text.split(' ');
  return { kind: 'time', time, unit };
}

/** Minutes until `at`, for "in {n} min" and the screen-reader sentence. */
export function minutesUntil(at: string, now: number): number {
  return Math.max(0, Math.floor((toMillis(at) - now) / 60_000));
}

/** "Northbound" from an E-02 direction label (DOC-37 §3.2). */
export function directionLabel(label: string | undefined, directionId: number): string {
  if (!label) return en.stops.direction.fallback(directionId);
  return en.stops.direction.labels[label] ?? label;
}
