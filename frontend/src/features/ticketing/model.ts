import type { components } from '@/api/generated/schema';
import { ticketingCopy } from '@/i18n/ticketing';
import { formatPercentWhole } from '@/lib/format';
import type { TicketingSearch, Trigger, Window } from '@/features/ticketing/search';
import { TRIGGERS } from '@/features/ticketing/search';

// Rules of Ticketing anomalies (DOC-36 screens/ops-console-ticketing): the period on the event axis, the filters
// sent to E-15, the hourly chart and the `summary` of E-16 (DOC-23 §9.5).

export type Anomaly = components['schemas']['TicketingAnomalyResponse'];

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;
/** E-15 accepts at most 31 days (DOC-32). */
export const MAX_SPAN_MS = 31 * DAY_MS;
/** Rows per E-15 page of the table (§4). */
export const PAGE_SIZE = 100;
/** The chart is built from at most this many anomalies (§4). */
export const CHART_LIMIT = 500;

const WINDOW_MS: Record<Window, number> = { '6h': 6 * HOUR_MS, '24h': DAY_MS, '7d': 7 * DAY_MS };

export type Period =
  { kind: 'window'; window: Window; spanMs: number } | { kind: 'fixed'; from: string; to: string; spanMs: number };

/** `from` and `to` win over `window` when both parse and `from < to`; the start is clamped to 31 days. */
export function resolvePeriod(search: Pick<TicketingSearch, 'window' | 'from' | 'to'>): Period {
  const from = search.from === undefined ? Number.NaN : Date.parse(search.from);
  const to = search.to === undefined ? Number.NaN : Date.parse(search.to);
  if (!Number.isNaN(from) && !Number.isNaN(to) && from < to) {
    const start = Math.max(from, to - MAX_SPAN_MS);
    return { kind: 'fixed', from: new Date(start).toISOString(), to: new Date(to).toISOString(), spanMs: to - start };
  }
  const window = search.window ?? '24h';
  return { kind: 'window', window, spanMs: WINDOW_MS[window] };
}

/** The instants sent to E-15: a window ends at `businessNow` (event axis, §2), not at the machine clock. */
export function periodRange(period: Period, businessNow: number): { from: string; to: string } {
  if (period.kind === 'fixed') return { from: period.from, to: period.to };
  return { from: new Date(businessNow - period.spanMs).toISOString(), to: new Date(businessNow).toISOString() };
}

/** What the cache key holds: the window, so that a sliding window keeps one entry and refetches slide with it. */
export function periodKey(period: Period): { window: Window } | { from: string; to: string } {
  return period.kind === 'window' ? { window: period.window } : { from: period.from, to: period.to };
}

export interface ListFilters {
  category?: string[];
  severity?: number[];
  trigger?: Trigger;
  salePointId?: string;
}

/** The E-15 filters of the URL. */
export function listFilters(search: TicketingSearch): ListFilters {
  return {
    category: search.category,
    severity: search.severity,
    trigger: search.trigger,
    salePointId: search.salePoint,
  };
}

export function isFiltered(search: TicketingSearch): boolean {
  return (
    (search.category?.length ?? 0) > 0 ||
    (search.severity?.length ?? 0) > 0 ||
    search.trigger !== undefined ||
    search.salePoint !== undefined
  );
}

export const CLEARED: Partial<TicketingSearch> = {
  category: undefined,
  severity: undefined,
  trigger: undefined,
  salePoint: undefined,
};

export interface HourlyCounts {
  /** Start of each hour of the range, oldest first (ISO). */
  hours: string[];
  /** Anomalies per hour, by trigger, in the order of `hours`. */
  byTrigger: Record<Trigger, number[]>;
}

/** Anomalies per hour of `detectedAt`, stacked by trigger (§4). Every hour of the range is there, empty ones at 0. */
export function hourlyCounts(
  items: readonly Pick<Anomaly, 'detectedAt' | 'trigger'>[],
  range: { from: string; to: string },
): HourlyCounts {
  const first = Math.floor(Date.parse(range.from) / HOUR_MS) * HOUR_MS;
  const end = Date.parse(range.to);
  const hours: string[] = [];
  for (let t = first; t < end; t += HOUR_MS) hours.push(new Date(t).toISOString());
  const byTrigger = Object.fromEntries(TRIGGERS.map((trigger) => [trigger, hours.map(() => 0)])) as Record<
    Trigger,
    number[]
  >;
  for (const item of items) {
    const index = Math.floor((Date.parse(item.detectedAt) - first) / HOUR_MS);
    const counts = (byTrigger as Partial<Record<string, number[]>>)[item.trigger];
    if (counts && index >= 0 && index < hours.length) counts[index] = (counts[index] ?? 0) + 1;
  }
  return { hours, byTrigger };
}

/** The 0–2 of `SeverityBadge`; null when triage has not set one. */
export function toSeverity(severity: number | undefined): 0 | 1 | 2 | null {
  return severity === 0 || severity === 1 || severity === 2 ? severity : null;
}

/** "refund 48%" when refunds triggered it, and "z 3.48" whenever there is a baseline (§4). */
export function signalParts(anomaly: Pick<Anomaly, 'trigger' | 'refundRatio' | 'zScore'>): string[] {
  const parts: string[] = [];
  if (anomaly.trigger !== 'VOLUME') parts.push(ticketingCopy.table.refund(formatPercentWhole(anomaly.refundRatio)));
  if (anomaly.zScore !== undefined) parts.push(ticketingCopy.table.z(anomaly.zScore.toFixed(2)));
  return parts;
}

/** Rows above the first one already seen are new (§6: tinted, or counted behind the pill when scrolled). */
export function newHeadIds(previous: ReadonlySet<string>, items: readonly { id: string }[]): string[] {
  const firstKnown = items.findIndex((item) => previous.has(item.id));
  return items.slice(0, firstKnown < 0 ? 0 : firstKnown).map((item) => item.id);
}

/** The absolute link of "Copy link" (§6). */
export function anomalyLink(origin: string, id: string): string {
  return `${origin}/ops/ticketing?anomaly=${encodeURIComponent(id)}`;
}

// ---------------------------------------------------------------------------------------------------------------------
// `summary` of E-16 (DOC-23 §9.5). It is free-form JSON on the wire, so every field is checked before use.

export interface Summary {
  salePointKind?: string;
  currency?: string;
  voidCount?: number;
  ticketTypes: { type: string; count: number }[];
  saleAmount?: { min: number; max: number };
  baseline?: { kind: string; windows?: number; mean?: number; stddev?: number; zScore?: number };
  previousWindows: { windowStart: string; txnCount: number }[];
  thresholds?: { volumeZ: number; volumeMinTxn: number; refundRatio: number; refundMinCount: number };
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const num = (value: unknown): number | undefined => {
  const n = typeof value === 'string' ? Number(value) : value;
  return typeof n === 'number' && Number.isFinite(n) ? n : undefined;
};
const str = (value: unknown): string | undefined => (typeof value === 'string' ? value : undefined);

export function parseSummary(raw: unknown): Summary {
  const summary = isRecord(raw) ? raw : {};
  const ticketTypes = isRecord(summary.ticketTypes)
    ? Object.entries(summary.ticketTypes).flatMap(([type, count]) => {
        const n = num(count);
        return n === undefined ? [] : [{ type, count: n }];
      })
    : [];
  const amount = isRecord(summary.saleAmount) ? summary.saleAmount : undefined;
  const min = num(amount?.min);
  const max = num(amount?.max);
  const baseline = isRecord(summary.baseline) ? summary.baseline : undefined;
  const thresholds = isRecord(summary.thresholds) ? summary.thresholds : undefined;
  const volumeZ = num(thresholds?.volumeZ);
  const volumeMinTxn = num(thresholds?.volumeMinTxn);
  const refundRatio = num(thresholds?.refundRatio);
  const refundMinCount = num(thresholds?.refundMinCount);
  const previous = Array.isArray(summary.previousWindows) ? summary.previousWindows : [];
  return {
    salePointKind: str(summary.salePointKind),
    currency: str(summary.currency),
    voidCount: num(summary.voidCount),
    ticketTypes,
    saleAmount: min !== undefined && max !== undefined ? { min, max } : undefined,
    baseline:
      baseline && str(baseline.kind)
        ? {
            kind: str(baseline.kind) ?? 'NONE',
            windows: num(baseline.windows),
            mean: num(baseline.mean),
            stddev: num(baseline.stddev),
            zScore: num(baseline.zScore),
          }
        : undefined,
    previousWindows: previous.flatMap((window) => {
      if (!isRecord(window)) return [];
      const windowStart = str(window.windowStart);
      const txnCount = num(window.txnCount);
      return windowStart !== undefined && txnCount !== undefined ? [{ windowStart, txnCount }] : [];
    }),
    thresholds:
      volumeZ !== undefined && volumeMinTxn !== undefined && refundRatio !== undefined && refundMinCount !== undefined
        ? { volumeZ, volumeMinTxn, refundRatio, refundMinCount }
        : undefined,
  };
}

/** The money of the summary in its currency (en-US locale, §6.1); USD when the summary says nothing. */
export function formatMoney(amount: number, currency = 'USD'): string {
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
  } catch {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount);
  }
}
