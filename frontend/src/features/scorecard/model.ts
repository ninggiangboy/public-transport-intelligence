import type { components } from '@/api/generated/schema';
import { toCsv } from '@/lib/csv';
import { otpByDay } from '@/lib/otp';
import { addDays, fromLocalInput } from '@/lib/time';

import type { Sort } from '@/features/scorecard/search';

// Numbers of the Route scorecard (DOC-36 screens/route-scorecard §2, §4).

type OtpItem = components['schemas']['Item'];
type RouteItem = components['schemas']['RouteItemResponse'];
type DelayBucket = components['schemas']['DelayBucketResponse'];

/** E-03 and E-14 accept at most 31 days (DOC-32). */
export const MAX_DAYS = 31;
const WEEK_DAYS = 7;
const DAY_MS = 86_400_000;

/** GTFS route types of each mode filter (§4): bus and trolleybus, then tram, subway and rail. */
export const MODE_TYPES = { bus: [3, 11], rail: [0, 1, 2] } as const;
export type Mode = 'all' | keyof typeof MODE_TYPES;
export type RouteMode = keyof typeof MODE_TYPES;

/** Service dates, both included. */
export interface DayRange {
  from: string;
  to: string;
}

export function dayCount(range: DayRange): number {
  return Math.round((Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`)) / DAY_MS) + 1;
}

/**
 * The range of the URL, or 7 days ending yesterday. A range longer than 31 days, edited by hand, is cut to the 31 days
 * that end on `to` (UC-06 2a); `clamped` says so.
 */
export function resolveRange(
  search: { from?: string; to?: string },
  yesterday: string,
): DayRange & { clamped: boolean } {
  const to = search.to ?? yesterday;
  let from = search.from ?? addDays(to, 1 - WEEK_DAYS);
  if (from > to) from = addDays(to, 1 - WEEK_DAYS);
  if (dayCount({ from, to }) > MAX_DAYS) return { from: addDays(to, 1 - MAX_DAYS), to, clamped: true };
  return { from, to, clamped: false };
}

export const PRESETS = ['week', 'month'] as const;
export type Preset = (typeof PRESETS)[number];
const PRESET_DAYS: Record<Preset, number> = { week: WEEK_DAYS, month: MAX_DAYS };

/** "Week" and "Month": 7 or 31 days ending yesterday. */
export function presetRange(preset: Preset, yesterday: string): DayRange {
  return { from: addDays(yesterday, 1 - PRESET_DAYS[preset]), to: yesterday };
}

/** The preset the range matches, if any. */
export function presetOf(range: DayRange, yesterday: string): Preset | undefined {
  if (range.to !== yesterday) return undefined;
  return (Object.keys(PRESET_DAYS) as Preset[]).find((preset) => dayCount(range) === PRESET_DAYS[preset]);
}

/** The period of the same length just before (the KPI deltas). */
export function previousRange(range: DayRange): DayRange {
  const to = addDays(range.from, -1);
  return { from: addDays(to, 1 - dayCount(range)), to };
}

/** Days as instants for E-03 and E-12: 00:00 agency time of the first day to 00:00 of the day after the last (§2). */
export function rangeInstants(range: DayRange, timeZone: string): { from: string; to: string } {
  return {
    from: new Date(fromLocalInput(`${range.from}T00:00`, timeZone)).toISOString(),
    to: new Date(fromLocalInput(`${addDays(range.to, 1)}T00:00`, timeZone)).toISOString(),
  };
}

export function routeModeOf(routeType: number | undefined): RouteMode | undefined {
  if (routeType === undefined) return undefined;
  return (Object.keys(MODE_TYPES) as RouteMode[]).find((mode) =>
    (MODE_TYPES[mode] as readonly number[]).includes(routeType),
  );
}

/** The mode filter a `routeType` list of the URL stands for. */
export function modeOf(routeTypes: readonly number[] | undefined): Mode {
  if (!routeTypes || routeTypes.length === 0) return 'all';
  return (
    (Object.keys(MODE_TYPES) as RouteMode[]).find((mode) =>
      routeTypes.every((type) => (MODE_TYPES[mode] as readonly number[]).includes(type)),
    ) ?? 'all'
  );
}

/** Items of the routes whose type is in `routeTypes`; every item without a filter. */
export function filterByType(
  items: readonly OtpItem[],
  routeTypes: readonly number[] | undefined,
  routes: ReadonlyMap<string, RouteItem>,
): OtpItem[] {
  if (!routeTypes || routeTypes.length === 0) return [...items];
  return items.filter((item) => {
    const type = routes.get(item.routeId)?.routeType;
    return type !== undefined && routeTypes.includes(type);
  });
}

/** Routes with data per mode, for "Bus {n}" and "Rail {n}". */
export function countByMode(items: readonly OtpItem[], routes: ReadonlyMap<string, RouteItem>) {
  const counts: Record<RouteMode, number> = { bus: 0, rail: 0 };
  for (const item of items) {
    const mode = routeModeOf(routes.get(item.routeId)?.routeType);
    if (mode) counts[mode] += 1;
  }
  return counts;
}

export interface Totals {
  observations: number;
  trips: number;
  /** Percentages 0–100; `undefined` without observations. */
  otp: number | undefined;
  early: number | undefined;
  late: number | undefined;
}

/** Counters added up over the routes, then divided (E-14). */
export function totals(items: readonly OtpItem[]): Totals {
  const sum = (pick: (item: OtpItem) => number) => items.reduce((total, item) => total + pick(item), 0);
  const observations = sum((item) => item.observationCount);
  const percent = (count: number) => (observations === 0 ? undefined : (count * 100) / observations);
  return {
    observations,
    trips: sum((item) => item.tripCount),
    otp: percent(sum((item) => item.onTimeCount)),
    early: percent(sum((item) => item.earlyCount)),
    late: percent(sum((item) => item.lateCount)),
  };
}

export function earlyPercent(item: OtpItem): number {
  return item.observationCount === 0 ? 0 : (item.earlyCount * 100) / item.observationCount;
}

export function latePercent(item: OtpItem): number {
  return item.observationCount === 0 ? 0 : (item.lateCount * 100) / item.observationCount;
}

/** The on-time window of the period: the same on every route and day, or `mixed` when it changed (FR-08.2). */
export function tolerances(
  items: readonly OtpItem[],
): { mixed: false; early: number; late: number } | { mixed: true } | undefined {
  if (items.some((item) => item.mixedTolerances)) return { mixed: true };
  const windows = new Set(
    items.flatMap((item) =>
      item.earlyToleranceSeconds === undefined || item.lateToleranceSeconds === undefined
        ? []
        : [`${item.earlyToleranceSeconds}:${item.lateToleranceSeconds}`],
    ),
  );
  if (windows.size > 1) return { mixed: true };
  const [only] = windows;
  if (only === undefined) return undefined;
  const [early = 0, late = 0] = only.split(':').map(Number);
  return { mixed: false, early, late };
}

const routeNumber = new Intl.Collator('en', { numeric: true });

/** Worst first (OTP up, then route id, as E-14 sorts), or by route number (feed order, then the number). */
export function sortItems(items: readonly OtpItem[], sort: Sort, routes: ReadonlyMap<string, RouteItem>): OtpItem[] {
  const sorted = [...items];
  if (sort === 'otp') {
    return sorted.sort((a, b) => a.otpPercentage - b.otpPercentage || a.routeId.localeCompare(b.routeId));
  }
  const order = (item: OtpItem) => routes.get(item.routeId)?.sortOrder ?? Number.MAX_SAFE_INTEGER;
  const name = (item: OtpItem) => routes.get(item.routeId)?.displayName ?? item.routeId;
  return sorted.sort((a, b) => order(a) - order(b) || routeNumber.compare(name(a), name(b)));
}

/** OTP per day of each mode that has data, weighted by observations (§4). */
export function otpByDayAndMode(items: readonly OtpItem[], routes: ReadonlyMap<string, RouteItem>) {
  const byMode = new Map<RouteMode, OtpItem[]>();
  for (const item of items) {
    const mode = routeModeOf(routes.get(item.routeId)?.routeType);
    if (mode) byMode.set(mode, [...(byMode.get(mode) ?? []), item]);
  }
  return (Object.keys(MODE_TYPES) as RouteMode[]).flatMap((mode) => {
    const days = otpByDay(byMode.get(mode) ?? []);
    return days.length > 0 ? [{ mode, days }] : [];
  });
}

/** The daily OTP of one route in date order, for its sparkline. */
export function dailySeries(item: OtpItem): number[] {
  return [...item.daily]
    .filter((day) => day.observationCount > 0)
    .sort((a, b) => a.serviceDate.localeCompare(b.serviceDate))
    .map((day) => day.otpPercentage);
}

const round2 = (value: number) => Math.round(value * 100) / 100;

/** The table as CSV (§4 "Export"): the columns of the table, numbers without formatting. */
export function scorecardCsv(
  header: readonly string[],
  items: readonly OtpItem[],
  routes: ReadonlyMap<string, RouteItem>,
): string {
  return toCsv(
    header,
    items.map((item, index) => {
      const route = routes.get(item.routeId);
      return [
        index + 1,
        [route?.displayName ?? item.routeId, route?.longName].filter(Boolean).join(' '),
        round2(item.otpPercentage),
        dailySeries(item).map(round2).join(' '),
        round2(earlyPercent(item)),
        round2(latePercent(item)),
        item.observationCount,
        item.tripCount,
      ];
    }),
  );
}

/** E-03 by hour of the week as cells of the 7 × 24 heatmap: Monday on top, midnight on the left. */
export function heatCells(items: readonly DelayBucket[]) {
  return items.flatMap((item) =>
    item.dayOfWeek === undefined || item.hourOfDay === undefined
      ? []
      : [{ row: item.dayOfWeek - 1, col: item.hourOfDay, value: item.avgDelaySeconds, count: item.observationCount }],
  );
}

/** The top of the bar scale of a stop profile: the largest 90th percentile, at least a minute. */
export function profileScale(items: readonly { avgDelaySeconds?: number; p90DelaySeconds?: number }[]): number {
  return Math.max(60, ...items.map((item) => Math.max(item.p90DelaySeconds ?? 0, item.avgDelaySeconds ?? 0))) * 1.1;
}
