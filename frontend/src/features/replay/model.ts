import type { components } from '@/api/generated/schema';
import { SETTLE_MS } from '@/features/replay/schema';
import type { ReplaySearch, ReplaySource } from '@/features/replay/search';
import { addDays, fromLocalInput, parseIsoDuration, zonedDate } from '@/lib/time';

// Pure logic of the Replay screen (DOC-36 screens/ops-console-replay): presets, per-source volumes, the hourly bars,
// the business-time line and the statistics of a finished replay.

type Schemas = components['schemas'];
export type Replay = Schemas['ReplayResponse'];
export type ReplayEstimate = Schemas['ReplayEstimateResponse'];
export type JobSummary = Schemas['JobSummaryResponse'];

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;

/** The Kafka topic of each source (DOC-22 §4.3), shown in mono on its card. */
export const TOPICS: Record<ReplaySource, string> = {
  GTFS_RT_TRIP_UPDATE: 'gtfs.trip_updates',
  GTFS_RT_VEHICLE_POSITION: 'gtfs.vehicle_positions',
  TICKETING_SALES: 'ticketing.sales.cdc',
  TICKETING_SALE_POINTS: 'ticketing.sale_points.cdc',
};

export const ACTIVE_STATUSES = ['PENDING', 'RUNNING'] as const;

export function isActive(status: string): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

export type Preset = 'lastHour' | 'last24h' | 'yesterday';

/**
 * The range of a preset on the real clock. "Last hour" and "Last 24 hours" end at the settle limit (now − 10 min),
 * since a range that ends later is refused (§4); "Yesterday" is the agency's calendar day before today.
 */
export function presetRange(preset: Preset, now: number, timeZone: string): { fromTs: string; toTs: string } {
  if (preset === 'yesterday') {
    const today = zonedDate(now, timeZone);
    const from = fromLocalInput(`${addDays(today, -1)}T00:00`, timeZone);
    const to = fromLocalInput(`${today}T00:00`, timeZone);
    return { fromTs: new Date(from).toISOString(), toTs: new Date(to).toISOString() };
  }
  // Whole minutes, as the inputs show them.
  const to = Math.floor((now - SETTLE_MS) / MINUTE_MS) * MINUTE_MS;
  const from = to - (preset === 'lastHour' ? HOUR_MS : 24 * HOUR_MS);
  return { fromTs: new Date(from).toISOString(), toTs: new Date(to).toISOString() };
}

/** Records read per source over the summary (the "{n} / 24 h" of each card). */
export function volumeBySource(summary: JobSummary | undefined): Partial<Record<string, number>> {
  const out: Partial<Record<string, number>> = {};
  for (const series of summary?.stream ?? []) {
    out[series.source] = series.points.reduce((total, point) => total + point.read, 0);
  }
  return out;
}

export interface HourBar {
  start: number;
  read: number;
  /** The hour overlaps the chosen range. */
  selected: boolean;
}

/**
 * The 24 hourly bars of a source, oldest first, from the hourly summary; bars that overlap `[from, to)` are selected
 * (none when the range lies outside the 24 hours).
 */
export function hourBars(
  summary: JobSummary | undefined,
  source: string | undefined,
  range: { from: number; to: number } | undefined,
): HourBar[] {
  const series = summary?.stream.find((item) => item.source === source);
  if (!series) return [];
  return [...series.points]
    .map((point) => ({ start: Date.parse(point.bucketStart), read: point.read }))
    .sort((a, b) => a.start - b.start)
    .slice(-24)
    .map((bar) => ({
      ...bar,
      selected: range !== undefined && bar.start < range.to && bar.start + HOUR_MS > range.from,
    }));
}

/** Milliseconds of `clockOffset` (E-60), which may be negative ("-PT2H"); 0 when absent or unreadable. */
export function offsetMs(clockOffset: string | undefined): number {
  if (!clockOffset) return 0;
  const negative = clockOffset.startsWith('-');
  const ms = parseIsoDuration(negative ? clockOffset.slice(1) : clockOffset);
  if (Number.isNaN(ms)) return 0;
  return negative ? -ms : ms;
}

/** The form's prefill from the URL (RB-11 and lineage links), read once when the form opens (§2). */
export function prefill(search: ReplaySearch) {
  return {
    source: search.source,
    fromTs: search.from ? new Date(search.from).toISOString() : '',
    toTs: search.to ? new Date(search.to).toISOString() : '',
    recomputeAnalytics: search.recompute !== '0',
  };
}

/** The search of "Replay again": the form prefilled with the replay's source, range and option (§6). */
export function replayAgainSearch(replay: Replay): Partial<ReplaySearch> {
  return {
    tab: 'new',
    replay: undefined,
    ...(isSource(replay.source) ? { source: replay.source } : {}),
    ...(replay.fromTs ? { from: replay.fromTs } : {}),
    ...(replay.toTs ? { to: replay.toTs } : {}),
    recompute: replay.recomputeAnalytics ? '1' : '0',
  };
}

function isSource(source: string): source is ReplaySource {
  return Object.hasOwn(TOPICS, source);
}

/** The progress of a running replay in percent, or undefined without an estimate (indeterminate bar). */
export function progressPercent(read: number, estimate: number | undefined): number | undefined {
  if (estimate === undefined || estimate <= 0) return undefined;
  return Math.min(100, Math.round((read / estimate) * 100));
}

// ---------------------------------------------------------------------------------------------------------------------
// Statistics of a finished replay (DOC-22 §4.6, camelCase in E-52)

export const STAT_KEYS = [
  'objects',
  'linesRead',
  'filtered',
  'processed',
  'written',
  'duplicate',
  'skipped',
  'dlqInserted',
  'dlqUpdated',
  'dlqResolved',
] as const;
export type StatKey = (typeof STAT_KEYS)[number];

export interface ReplayStats {
  counts: Partial<Record<StatKey, number>>;
  minEventTs?: string;
  maxEventTs?: string;
  /** Per detector of `analytics` (DOC-23 §11.7). */
  analytics: { detector: string; scopes: number; upserted: number; deleted: number }[];
}

const num = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
const str = (value: unknown) => (typeof value === 'string' && value !== '' ? value : undefined);

/** Reads the free-form `stats` of E-52; keys it does not know are left out. */
export function readStats(stats: Replay['stats']): ReplayStats | undefined {
  if (!stats) return undefined;
  const counts: Partial<Record<StatKey, number>> = {};
  for (const key of STAT_KEYS) {
    const value = num(stats[key]);
    if (value !== undefined) counts[key] = value;
  }
  const analytics: ReplayStats['analytics'] = [];
  const byDetector = stats.analytics;
  if (typeof byDetector === 'object' && byDetector !== null) {
    for (const [detector, value] of Object.entries(byDetector as Record<string, unknown>)) {
      if (typeof value !== 'object' || value === null) continue;
      const entry = value as Record<string, unknown>;
      analytics.push({
        detector,
        scopes: num(entry.scopes) ?? 0,
        upserted: num(entry.upserted) ?? 0,
        deleted: num(entry.deleted) ?? 0,
      });
    }
  }
  const minEventTs = str(stats.minEventTs);
  const maxEventTs = str(stats.maxEventTs);
  return { counts, ...(minEventTs ? { minEventTs } : {}), ...(maxEventTs ? { maxEventTs } : {}), analytics };
}

/** "/ops/dlq?status=RESOLVED&source=…&from=…": the dead letters a replay resolved (§6.1). */
export function resolvedHref(replay: Replay): string {
  const params = new URLSearchParams({ status: 'RESOLVED', source: replay.source });
  if (replay.startedAt) params.set('from', replay.startedAt);
  return `/ops/dlq?${params.toString()}`;
}

export const HREF = {
  deadLetter: (id: string) => `/ops/dlq?id=${encodeURIComponent(id)}`,
  run: (runId: string) => `/ops/jobs?run=${encodeURIComponent(runId)}`,
  runJob: '/ops/controls#run-job',
} as const;

/** "job:4127" → "4127". */
export function runNumber(replay: Pick<Replay, 'runId' | 'jobExecutionId'>): string | undefined {
  if (replay.jobExecutionId !== undefined) return String(replay.jobExecutionId);
  return replay.runId?.replace(/^job:/, '');
}
