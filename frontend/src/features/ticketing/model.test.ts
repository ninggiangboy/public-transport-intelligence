import { describe, expect, it } from 'vitest';

import {
  anomalyLink,
  hourlyCounts,
  MAX_SPAN_MS,
  newHeadIds,
  parseSummary,
  periodKey,
  periodRange,
  resolvePeriod,
  signalParts,
} from '@/features/ticketing/model';
import { ticketingSearch } from '@/features/ticketing/search';

const NOW = Date.parse('2026-09-29T21:19:35Z');

describe('period (§2)', () => {
  it('defaults to 24 hours ending at businessNow and keys by the window', () => {
    const period = resolvePeriod({});
    expect(periodRange(period, NOW)).toEqual({ from: '2026-09-28T21:19:35.000Z', to: '2026-09-29T21:19:35.000Z' });
    expect(periodKey(period)).toEqual({ window: '24h' });
    expect(periodRange(resolvePeriod({ window: '7d' }), NOW).from).toBe('2026-09-22T21:19:35.000Z');
  });

  it('takes from and to over the window, at most 31 days', () => {
    const period = resolvePeriod({ window: '6h', from: '2026-01-01T00:00:00Z', to: '2026-09-29T00:00:00Z' });
    expect(period.kind).toBe('fixed');
    expect(period.spanMs).toBe(MAX_SPAN_MS);
    expect(periodRange(period, NOW).to).toBe('2026-09-29T00:00:00.000Z');
    expect(resolvePeriod({ from: '2026-09-29T00:00:00Z', to: '2026-09-28T00:00:00Z' }).kind).toBe('window');
  });
});

describe('search', () => {
  it('drops bad values and accepts single values for lists', () => {
    expect(
      ticketingSearch.parse({ window: '1y', category: 'unclassified', severity: '2', trigger: 'NOPE', anomaly: 'a' }),
    ).toEqual({ category: ['unclassified'], severity: [2], anomaly: 'a' });
  });
});

describe('hourlyCounts', () => {
  it('counts anomalies per hour of detectedAt by trigger, with empty hours at 0', () => {
    const counts = hourlyCounts(
      [
        { detectedAt: '2026-09-29T18:15:00Z', trigger: 'VOLUME' },
        { detectedAt: '2026-09-29T18:45:00Z', trigger: 'VOLUME' },
        { detectedAt: '2026-09-29T20:00:00Z', trigger: 'REFUND_RATIO' },
        { detectedAt: '2026-09-29T20:30:00Z', trigger: 'BOTH' },
        { detectedAt: '2026-09-28T00:00:00Z', trigger: 'VOLUME' },
      ],
      { from: '2026-09-29T18:10:00Z', to: '2026-09-29T21:10:00Z' },
    );
    expect(counts.hours).toEqual([
      '2026-09-29T18:00:00.000Z',
      '2026-09-29T19:00:00.000Z',
      '2026-09-29T20:00:00.000Z',
      '2026-09-29T21:00:00.000Z',
    ]);
    expect(counts.byTrigger).toEqual({ VOLUME: [2, 0, 0, 0], REFUND_RATIO: [0, 0, 1, 0], BOTH: [0, 0, 1, 0] });
  });
});

describe('rows', () => {
  it('finds the rows above the first one already seen', () => {
    const items = [{ id: 'c' }, { id: 'b' }, { id: 'a' }];
    expect(newHeadIds(new Set(['a', 'b']), items)).toEqual(['c']);
    expect(newHeadIds(new Set(['x']), items)).toEqual([]);
  });

  it('writes the signal by trigger', () => {
    expect(signalParts({ trigger: 'VOLUME', refundRatio: 0.01, zScore: 23.74 })).toEqual(['z 23.74']);
    expect(signalParts({ trigger: 'REFUND_RATIO', refundRatio: 0.48, zScore: 3.48 })).toEqual(['refund 48%', 'z 3.48']);
    expect(signalParts({ trigger: 'BOTH', refundRatio: 0.4 })).toEqual(['refund 40%']);
  });

  it('builds the absolute link of an anomaly', () => {
    expect(anomalyLink('https://pti.test', 'a b')).toBe('https://pti.test/ops/ticketing?anomaly=a%20b');
  });
});

describe('parseSummary (DOC-23 §9.5)', () => {
  it('reads the fields it knows and ignores the rest', () => {
    const summary = parseSummary({
      salePointKind: 'KIOSK',
      ticketTypes: { SINGLE: 90, DAY: '6', bad: 'x' },
      saleAmount: { min: 2.5, max: 5 },
      baseline: { kind: 'NONE' },
      previousWindows: [{ windowStart: 'w', txnCount: 13 }, { txnCount: 1 }, 'x'],
      thresholds: { volumeZ: 3 },
    });
    expect(summary).toMatchObject({
      salePointKind: 'KIOSK',
      ticketTypes: [
        { type: 'SINGLE', count: 90 },
        { type: 'DAY', count: 6 },
      ],
      saleAmount: { min: 2.5, max: 5 },
      baseline: { kind: 'NONE' },
      previousWindows: [{ windowStart: 'w', txnCount: 13 }],
      thresholds: undefined,
    });
    expect(parseSummary(undefined)).toEqual(
      expect.objectContaining({ ticketTypes: [], previousWindows: [], baseline: undefined }),
    );
  });
});
