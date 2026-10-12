import { describe, expect, it } from 'vitest';

import {
  hourBars,
  offsetMs,
  prefill,
  presetRange,
  progressPercent,
  readStats,
  replayAgainSearch,
  resolvedHref,
  type JobSummary,
  type Replay,
} from '@/features/replay/model';
import { checkReplayForm, MAX_RANGE_MS, SETTLE_MS } from '@/features/replay/schema';
import { replaySearch } from '@/features/replay/search';
import { replayCopy } from '@/i18n/replay';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const NOW = Date.parse('2026-09-29T21:19:35Z');
const ZONE = 'America/Chicago';

const iso = (ms: number) => new Date(ms).toISOString();

describe('checkReplayForm (§4)', () => {
  const valid = {
    source: 'GTFS_RT_TRIP_UPDATE' as const,
    fromTs: iso(NOW - 2 * HOUR),
    toTs: iso(NOW - HOUR),
    recomputeAnalytics: true,
  };

  it('accepts a valid range', () => {
    expect(checkReplayForm(valid, NOW)).toEqual({ ok: true, body: valid });
  });

  it('gives each rule its message on its field', () => {
    const errors = (patch: object) => {
      const result = checkReplayForm({ ...valid, ...patch }, NOW);
      return result.ok ? {} : result.errors;
    };
    expect(errors({ toTs: valid.fromTs })).toEqual({ toTs: replayCopy.errors.order });
    expect(errors({ fromTs: iso(NOW - HOUR - MAX_RANGE_MS - MINUTE) })).toEqual({
      fromTs: undefined,
      toTs: replayCopy.errors.maxRange,
    });
    expect(errors({ toTs: iso(NOW - SETTLE_MS + MINUTE) })).toEqual({ toTs: replayCopy.errors.settle });
    expect(errors({ fromTs: iso(NOW - 30 * 24 * HOUR), toTs: iso(NOW - 25 * 24 * HOUR) })).toEqual({
      fromTs: replayCopy.errors.maxAge,
    });
  });

  it('shows no message for a field left empty', () => {
    expect(checkReplayForm({ ...valid, source: undefined, toTs: '' }, NOW)).toEqual({ ok: false, errors: {} });
  });
});

describe('presetRange', () => {
  it('ends the sliding presets at the settle limit, on the minute', () => {
    const range = presetRange('lastHour', NOW, ZONE);
    expect(range.toTs).toBe('2026-09-29T21:09:00.000Z');
    expect(Date.parse(range.toTs) - Date.parse(range.fromTs)).toBe(HOUR);
    expect(Date.parse(presetRange('last24h', NOW, ZONE).fromTs)).toBe(Date.parse(range.toTs) - 24 * HOUR);
  });

  it('takes yesterday in the agency zone', () => {
    expect(presetRange('yesterday', NOW, ZONE)).toEqual({
      fromTs: '2026-09-28T05:00:00.000Z',
      toTs: '2026-09-29T05:00:00.000Z',
    });
  });
});

describe('hourBars', () => {
  const summary: JobSummary = {
    batchJobs: { completed: 0, failed: 0, running: 0, stopped: 0 },
    bucket: '1h',
    from: '2026-09-29T00:00:00Z',
    to: '2026-09-29T03:00:00Z',
    stream: [
      {
        source: 'TICKETING_SALES',
        points: ['02', '00', '01'].map((hour) => ({
          bucketStart: `2026-09-29T${hour}:00:00Z`,
          batches: 1,
          duplicate: 0,
          failedBatches: 0,
          p95BatchMs: 1,
          read: Number(hour) + 10,
          skipped: 0,
          written: 0,
        })),
      },
    ],
  };

  it('sorts the hours and marks those that overlap the range', () => {
    const bars = hourBars(summary, 'TICKETING_SALES', {
      from: Date.parse('2026-09-29T01:30:00Z'),
      to: Date.parse('2026-09-29T02:00:00Z'),
    });
    expect(bars.map((bar) => [bar.read, bar.selected])).toEqual([
      [10, false],
      [11, true],
      [12, false],
    ]);
  });

  it('has no bars for a source without data', () => {
    expect(hourBars(summary, 'GTFS_RT_TRIP_UPDATE', undefined)).toEqual([]);
  });
});

describe('helpers', () => {
  it('reads the clock offset, negative ones too', () => {
    expect(offsetMs('PT14H30M')).toBe(14.5 * HOUR);
    expect(offsetMs('-PT2H')).toBe(-2 * HOUR);
    expect(offsetMs('PT0S')).toBe(0);
    expect(offsetMs('bad')).toBe(0);
  });

  it('turns the read count into a percentage of the estimate', () => {
    expect(progressPercent(412_000, 1_152_000)).toBe(36);
    expect(progressPercent(5, 2)).toBe(100);
    expect(progressPercent(5, undefined)).toBeUndefined();
  });

  it('reads the statistics and leaves unknown keys out', () => {
    expect(
      readStats({ linesRead: 4, written: 3, analytics: { TICKETING: { scopes: 2 } }, durationMs: 9, minEventTs: 'a' }),
    ).toEqual({
      counts: { linesRead: 4, written: 3 },
      minEventTs: 'a',
      analytics: [{ detector: 'TICKETING', scopes: 2, upserted: 0, deleted: 0 }],
    });
    expect(readStats(undefined)).toBeUndefined();
  });

  it('builds the links of the drawer and "Replay again"', () => {
    const replay: Replay = {
      id: 'r',
      kind: 'RAW_RANGE',
      source: 'TICKETING_SALES',
      fromTs: '2026-09-28T00:00:00Z',
      toTs: '2026-09-29T00:00:00Z',
      recomputeAnalytics: false,
      status: 'DONE',
      requestedBy: 'user:operator',
      requestedAt: '2026-09-29T00:00:00Z',
      startedAt: '2026-09-29T00:00:02Z',
    };
    expect(resolvedHref(replay)).toBe('/ops/dlq?status=RESOLVED&source=TICKETING_SALES&from=2026-09-29T00%3A00%3A02Z');
    expect(replayAgainSearch(replay)).toEqual({
      tab: 'new',
      replay: undefined,
      source: 'TICKETING_SALES',
      from: replay.fromTs,
      to: replay.toTs,
      recompute: '0',
    });
  });
});

describe('replaySearch', () => {
  it('drops bad values and reads recompute as a number or a string', () => {
    expect(
      replaySearch.parse({
        tab: 'x',
        source: 'GTFS_STATIC',
        recompute: 1,
        status: 'RUNNING',
        from: 'nope',
        kind: 'RAW_RANGE',
      }),
    ).toEqual({ recompute: '1', status: ['RUNNING'], kind: 'RAW_RANGE' });
    expect(prefill(replaySearch.parse({ recompute: 0 })).recomputeAnalytics).toBe(false);
    expect(prefill(replaySearch.parse({})).recomputeAnalytics).toBe(true);
  });
});
