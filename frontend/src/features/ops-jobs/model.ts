import type { InfiniteData } from '@tanstack/react-query';

import type { WithAsOf } from '@/api/client';
import type { components } from '@/api/generated/schema';
import type { Bucket, Kind, PipelineSearch, Window } from '@/features/ops-jobs/search';
import { pipelineCopy } from '@/i18n/pipeline';
import { actorName, formatCount } from '@/lib/format';

// Pure logic of Pipeline (DOC-36 screens/ops-console-jobs): the period and bucket of the URL, the numbers of the stage
// diagram and the throughput chart, and how a run is labelled. Times are on the audit axis (DOC-34 §8): the machine
// clock, passed in as `now`.

type Schemas = components['schemas'];
export type JobSummary = Schemas['JobSummaryResponse'];
export type SummaryPoint = Schemas['PointResponse'];
export type JobRun = Schemas['JobRunResponse'];
export type JobRunDetail = Schemas['JobRunDetailResponse'];
export type JobRequest = Schemas['JobRequestResponse'];
export interface RunPage {
  items: JobRun[];
  nextCursor?: string;
}

const copy = pipelineCopy.pipeline;
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
/** E-30 and E-31 accept at most 24 hours (DOC-15 §5). */
export const MAX_SPAN_MS = 24 * HOUR_MS;
/** E-31: buckets × sources ≤ 1,440 × 4, so at most 1,440 buckets per source. */
const MAX_BUCKETS = 1_440;
export const RUN_PAGE = 100;
/** Spring Batch's ExecutionContext is cut to this many characters by E-32 (DOC-19 §3.1). */
export const CONTEXT_LIMIT = 2_500;

export const WINDOW_MS: Record<Window, number> = {
  '15m': 15 * MINUTE_MS,
  '1h': HOUR_MS,
  '6h': 6 * HOUR_MS,
  '24h': 24 * HOUR_MS,
};
export const BUCKET_MS: Record<Bucket, number> = {
  '1m': MINUTE_MS,
  '5m': 5 * MINUTE_MS,
  '15m': 15 * MINUTE_MS,
  '1h': HOUR_MS,
};
const BUCKET_ORDER: readonly Bucket[] = ['1m', '5m', '15m', '1h'];

// ---------------------------------------------------------------------------------------------------------------------
// Period and bucket (§2)

/** A sliding window (`to` is now at each query) or a fixed range; `spanMs` is its length. */
export type Period =
  { kind: 'window'; window: Window; spanMs: number } | { kind: 'fixed'; from: string; to: string; spanMs: number };

/** `from`/`to` when both are valid (longer than 24 h: the 24 h that end at `to`), else `window`, default 1 hour. */
export function resolvePeriod(search: Pick<PipelineSearch, 'window' | 'from' | 'to'>): Period {
  const from = search.from === undefined ? Number.NaN : Date.parse(search.from);
  const to = search.to === undefined ? Number.NaN : Date.parse(search.to);
  if (!Number.isNaN(from) && !Number.isNaN(to) && from < to) {
    const start = Math.max(from, to - MAX_SPAN_MS);
    return {
      kind: 'fixed',
      from: new Date(start).toISOString(),
      to: new Date(to).toISOString(),
      spanMs: to - start,
    };
  }
  const window = search.window ?? '1h';
  return { kind: 'window', window, spanMs: WINDOW_MS[window] };
}

/** The instants of a period now. */
export function periodInstants(period: Period, now: number): { from: string; to: string } {
  if (period.kind === 'fixed') return { from: period.from, to: period.to };
  return { from: new Date(now - period.spanMs).toISOString(), to: new Date(now).toISOString() };
}

/** What the cache key holds: the window, so that a sliding window keeps one entry (and `job.run` may insert). */
export function periodKey(period: Period): { window: Window } | { from: string; to: string } {
  return period.kind === 'window' ? { window: period.window } : { from: period.from, to: period.to };
}

/** ≤ 1 hour → 1m; ≤ 6 hours → 5m; longer → 15m (§2). */
export function defaultBucket(spanMs: number): Bucket {
  if (spanMs <= HOUR_MS) return '1m';
  if (spanMs <= 6 * HOUR_MS) return '5m';
  return '15m';
}

/** The bucket of the URL, raised until E-31 accepts it (so its 400 for too many buckets never happens). */
export function resolveBucket(bucket: Bucket | undefined, spanMs: number): Bucket {
  const start = BUCKET_ORDER.indexOf(bucket ?? defaultBucket(spanMs));
  return BUCKET_ORDER.slice(start).find((b) => spanMs / BUCKET_MS[b] <= MAX_BUCKETS) ?? '1h';
}

export function bucketMs(bucket: string): number {
  return (BUCKET_MS as Record<string, number | undefined>)[bucket] ?? MINUTE_MS;
}

// ---------------------------------------------------------------------------------------------------------------------
// Stage diagram (§4.1)

/** The newest bucket that has ended by `summary.to`: the current one is still filling. */
function lastCompleteStart(summary: JobSummary): number | undefined {
  const size = bucketMs(summary.bucket);
  const to = Date.parse(summary.to);
  let latest: number | undefined;
  for (const series of summary.stream) {
    for (const point of series.points) {
      const start = Date.parse(point.bucketStart);
      if (start + size <= to && (latest === undefined || start > latest)) latest = start;
    }
  }
  return latest;
}

export interface StreamStage {
  /** Messages read per second in the last complete bucket, every source. */
  readPerSecond: number;
  writtenPerSecond: number;
  batchesPerMinute: number;
  /** The slowest source's p95 in the last complete bucket; undefined without batches. */
  p95Ms?: number;
  /** Failed micro-batches in the last 5 minutes, the bucket still filling included. */
  failedRecently: number;
  /** Skipped over read, the whole summary. */
  skippedRatio?: number;
  /** Written per bucket, oldest first, for the Warehouse sparkline. */
  writtenTrend: number[];
}

/** The numbers of the Sources, etl-stream and Warehouse cards, from E-31 by minute over 15 minutes. */
export function streamStage(summary: JobSummary): StreamStage {
  const size = bucketMs(summary.bucket);
  const seconds = size / 1000;
  const last = lastCompleteStart(summary);
  const recentFrom = Date.parse(summary.to) - 5 * MINUTE_MS;
  const byStart = new Map<number, number>();
  let read = 0;
  let written = 0;
  let batches = 0;
  let p95: number | undefined;
  let failedRecently = 0;
  let readTotal = 0;
  let skippedTotal = 0;
  for (const series of summary.stream) {
    for (const point of series.points) {
      const start = Date.parse(point.bucketStart);
      readTotal += point.read;
      skippedTotal += point.skipped;
      if (start + size > recentFrom) failedRecently += point.failedBatches;
      if (last !== undefined && start <= last) byStart.set(start, (byStart.get(start) ?? 0) + point.written);
      if (start !== last) continue;
      read += point.read;
      written += point.written;
      batches += point.batches;
      if (point.batches > 0) p95 = Math.max(p95 ?? 0, point.p95BatchMs);
    }
  }
  return {
    readPerSecond: read / seconds,
    writtenPerSecond: written / seconds,
    batchesPerMinute: batches / (size / MINUTE_MS),
    ...(p95 === undefined ? {} : { p95Ms: p95 }),
    failedRecently,
    ...(readTotal > 0 ? { skippedRatio: skippedTotal / readTotal } : {}),
    writtenTrend: [...byStart.entries()].sort(([a], [b]) => a - b).map(([, value]) => value),
  };
}

const RATE_SMALL = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 });

/** "142", "3.4", "0": a per-second or per-minute rate. */
export function formatRate(value: number): string {
  return value >= 100 ? formatCount(Math.round(value)) : RATE_SMALL.format(value);
}

/** Any `etl.consumer.*.paused` flag on: the etl-stream card reads "Paused" (§4.1, E-55). */
export function consumerPaused(flags: readonly { key: string; value: unknown }[] | undefined): boolean {
  return (flags ?? []).some(
    (flag) => flag.key.startsWith('etl.consumer.') && flag.key.endsWith('.paused') && flag.value === true,
  );
}

// ---------------------------------------------------------------------------------------------------------------------
// Throughput (§4)

export type Metric = 'read' | 'written' | 'skipped';
export const METRICS: readonly Metric[] = ['read', 'written', 'skipped'];

export interface BucketTotals {
  batches: number;
  failedBatches: number;
  read: number;
  written: number;
  skipped: number;
  duplicate: number;
  /** The slowest source's p95; undefined without batches. */
  p95Ms?: number;
}

/** Every source added up per bucket start, oldest first. */
export function totalsByBucket(summary: JobSummary): Map<string, BucketTotals> {
  const out = new Map<string, BucketTotals>();
  const starts = [...new Set(summary.stream.flatMap((s) => s.points.map((p) => p.bucketStart)))].sort();
  for (const start of starts) {
    out.set(start, { batches: 0, failedBatches: 0, read: 0, written: 0, skipped: 0, duplicate: 0 });
  }
  for (const series of summary.stream) {
    for (const point of series.points) {
      const total = out.get(point.bucketStart);
      if (!total) continue;
      total.batches += point.batches;
      total.failedBatches += point.failedBatches;
      total.read += point.read;
      total.written += point.written;
      total.skipped += point.skipped;
      total.duplicate += point.duplicate;
      if (point.batches > 0) total.p95Ms = Math.max(total.p95Ms ?? 0, point.p95BatchMs);
    }
  }
  return out;
}

/** Whether no micro-batch ran in the whole summary: the consumer was idle (§7). */
export function noActivity(summary: JobSummary): boolean {
  return summary.stream.every((series) => series.points.every((point) => point.batches === 0));
}

/** A bucket without micro-batches is a gap, never a zero (§4, AC-7). */
export function totalSeries(totals: Map<string, BucketTotals>, metric: Metric) {
  return [...totals.entries()].map(([t, total]) => ({ t, v: total.batches === 0 ? null : total[metric] }));
}

export function sourceSeries(summary: JobSummary, metric: Metric) {
  return summary.stream.map((series) => ({
    source: series.source,
    points: series.points.map((point) => ({ t: point.bucketStart, v: point.batches === 0 ? null : point[metric] })),
  }));
}

export interface SourceRow {
  source: string;
  /** Messages per second in the last complete bucket; undefined when there is none. */
  rate?: number;
  skipped: number;
  duplicates: number;
}

/** One row per stream source of the summary (§4 "By source"). */
export function sourceRows(summary: JobSummary): SourceRow[] {
  const size = bucketMs(summary.bucket);
  const last = lastCompleteStart(summary);
  return summary.stream.map((series) => {
    const point = series.points.find((p) => Date.parse(p.bucketStart) === last);
    return {
      source: series.source,
      ...(point ? { rate: point.read / (size / 1000) } : {}),
      skipped: series.points.reduce((sum, p) => sum + p.skipped, 0),
      duplicates: series.points.reduce((sum, p) => sum + p.duplicate, 0),
    };
  });
}

// ---------------------------------------------------------------------------------------------------------------------
// Runs (§4, §6.1)

/** The jobs of DOC-19 §2 and the stream listeners, for the Job filter. */
export const BATCH_JOBS = [
  'AnalyticsRecomputeJob',
  'BatchMetadataCleanupJob',
  'DataQualityJob',
  'DedupRegistryCleanupJob',
  'DlqReplayJob',
  'EtaAggregationJob',
  'GtfsStaticLoadJob',
  'OpsRetentionJob',
  'OtpScorecardJob',
  'PartitionMaintenanceJob',
  'RawZoneReplayJob',
  'TicketingAnomalyJob',
] as const;
export const LISTENERS = [
  'gtfs-rt-vehicle-position',
  'gtfs-rt-trip-update',
  'ticketing-sales',
  'ticketing-sale-points',
] as const;
const BATCH_STATUSES = ['STARTING', 'STARTED', 'STOPPING', 'STOPPED', 'FAILED', 'COMPLETED', 'ABANDONED', 'UNKNOWN'];
const STREAM_STATUSES = ['COMPLETED', 'COMPLETED_WITH_SKIPS', 'FAILED'];

/** The names and statuses the filters offer for a kind (E-30). */
export function filterOptions(kind: Kind | undefined): { names: string[]; statuses: string[] } {
  if (kind === 'BATCH_JOB') return { names: [...BATCH_JOBS], statuses: BATCH_STATUSES };
  if (kind === 'STREAM') return { names: [...LISTENERS], statuses: STREAM_STATUSES };
  return { names: [...BATCH_JOBS, ...LISTENERS], statuses: [...new Set([...BATCH_STATUSES, ...STREAM_STATUSES])] };
}

export type Tab = 'all' | 'batch' | 'stream' | 'failed';
export const TABS: readonly Tab[] = ['all', 'batch', 'stream', 'failed'];

/** The tab the filters amount to: a kind, or exactly `status=FAILED`. */
export function tabOf(search: Pick<PipelineSearch, 'kind' | 'status'>): Tab {
  if (search.kind === 'BATCH_JOB') return 'batch';
  if (search.kind === 'STREAM') return 'stream';
  if (search.status?.length === 1 && search.status[0] === 'FAILED') return 'failed';
  return 'all';
}

/** The filters a tab writes; picking a tab clears the other one's filter. */
export function tabFilters(tab: Tab): { kind?: Kind; status?: string[] } {
  if (tab === 'batch') return { kind: 'BATCH_JOB', status: undefined };
  if (tab === 'stream') return { kind: 'STREAM', status: undefined };
  if (tab === 'failed') return { kind: undefined, status: ['FAILED'] };
  return { kind: undefined, status: undefined };
}

const RUNNING = new Set(['STARTING', 'STARTED', 'STOPPING']);

export function isRunning(status: string): boolean {
  return RUNNING.has(status);
}

/** Stop is offered while the job runs and has not been asked to stop (E-36). */
export function canStop(status: string): boolean {
  return status === 'STARTING' || status === 'STARTED';
}

export function isBatchJob(run: { kind: string }): boolean {
  return run.kind === 'BATCH_JOB';
}

/** The minute of a stream run id, `stream:<listener>:2026-09-29T21:18Z`. */
export function streamMinute(runId: string): string | undefined {
  const match = /:(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})Z$/.exec(runId);
  return match ? `${match[1]}:00Z` : undefined;
}

/** "Schedule", "Manual · operator", "Replay · operator", "Streaming" (§4). */
export function triggerLabel(run: { kind: string; request?: { type: string; requestedBy?: string } }): string {
  if (!isBatchJob(run)) return copy.trigger.streaming;
  const request = run.request;
  if (!request) return copy.trigger.schedule;
  const name = request.requestedBy ? actorName(request.requestedBy) : undefined;
  if (request.type === 'replay') return name ? copy.trigger.replay(name) : copy.trigger.replayAnonymous;
  return name ? copy.trigger.manual(name) : copy.trigger.manualAnonymous;
}

/** How long the run took, or has taken so far while it runs. */
export function runDurationMs(run: Pick<JobRun, 'durationMs' | 'startedAt' | 'endedAt' | 'status'>, now: number) {
  if (run.durationMs !== undefined) return run.durationMs;
  if (run.startedAt === undefined) return undefined;
  if (run.endedAt !== undefined) return Date.parse(run.endedAt) - Date.parse(run.startedAt);
  return isRunning(run.status) ? Math.max(0, now - Date.parse(run.startedAt)) : undefined;
}

/** `{"topic-3": [1200, 1260]}` → "p3: 1200–1260" (§6.1). */
export function formatOffsets(offsets: Record<string, unknown> | undefined): string {
  return Object.entries(offsets ?? {})
    .map(([key, value]) => {
      const partition = /-(\d+)$/.exec(key)?.[1];
      const label = partition === undefined ? key : `p${partition}`;
      const range = Array.isArray(value) ? value.map(String).join('–') : String(value);
      return `${label}: ${range}`;
    })
    .join(', ');
}

/** The heading line of a failed run's callout (§6.1). */
export function firstLine(text: string | undefined): string | undefined {
  const line = text?.split('\n', 1)[0]?.trim();
  return line === '' ? undefined : line;
}

/** The callout of a run that did not complete: danger, or warning with skips; none for a completed run. */
export function exitTone(status: string): 'danger' | 'warning' | undefined {
  if (status === 'COMPLETED' || isRunning(status)) return undefined;
  return status === 'COMPLETED_WITH_SKIPS' ? 'warning' : 'danger';
}

/**
 * The 60 s refresh of the stream rows (§5): the first page fetched again goes over the loaded pages. A run already
 * loaded is replaced where it is; a new one goes to the head, newest first.
 */
export function mergeHead(
  cached: InfiniteData<WithAsOf<RunPage>> | undefined,
  fresh: readonly JobRun[],
): InfiniteData<WithAsOf<RunPage>> | undefined {
  if (!cached) return cached;
  const byId = new Map(fresh.map((run) => [run.runId, run]));
  const seen = new Set<string>();
  const pages = cached.pages.map((page) => {
    const items = page.data.items.map((item) => {
      const next = byId.get(item.runId);
      if (!next) return item;
      seen.add(item.runId);
      return { ...item, ...next };
    });
    return { ...page, data: { ...page.data, items } };
  });
  const added = fresh.filter((run) => !seen.has(run.runId));
  const [head, ...rest] = pages;
  if (!head || added.length === 0) return { ...cached, pages };
  const items = [...added, ...head.data.items].sort(
    (a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? '') || b.runId.localeCompare(a.runId),
  );
  return { ...cached, pages: [{ ...head, data: { ...head.data, items } }, ...rest] };
}

// ---------------------------------------------------------------------------------------------------------------------
// Links

/** Paths this screen links to (DOC-34 §5.2). */
export const HREF = {
  pipeline: '/ops/jobs',
  deadLetters: '/ops/dlq',
  runJob: '/ops/controls#run-job',
  batch: (batchId: string) => `/ops/batches/${encodeURIComponent(batchId)}`,
  replay: (id: string) => `/ops/replay?tab=history&replay=${encodeURIComponent(id)}`,
} as const;

// ---------------------------------------------------------------------------------------------------------------------
// Lineage (§6.2)

/**
 * `/ops/dlq` around the batch: E-40 has no `batchId` filter, so the source and the batch's time (plus a minute) stand
 * in for it.
 */
export function deadLetterHref(lineage: { source?: string; startedAt?: string; endedAt?: string }): string {
  const params = new URLSearchParams();
  if (lineage.source) params.set('source', lineage.source);
  if (lineage.startedAt) params.set('from', lineage.startedAt);
  const end = lineage.endedAt ?? lineage.startedAt;
  if (end) params.set('to', new Date(Date.parse(end) + MINUTE_MS).toISOString());
  const query = params.toString();
  return query ? `/ops/dlq?${query}` : '/ops/dlq';
}

/** `/ops/jobs?run=…` with a fixed range around the run's start, so that the run is in the list (§6.2). */
export function runHref(runId: string, startedAt: string | undefined): string {
  const params = new URLSearchParams({ run: runId });
  if (startedAt) {
    const at = Date.parse(startedAt);
    params.set('from', new Date(at - 30 * MINUTE_MS).toISOString());
    params.set('to', new Date(at + 30 * MINUTE_MS).toISOString());
  }
  return `/ops/jobs?${params.toString()}`;
}
