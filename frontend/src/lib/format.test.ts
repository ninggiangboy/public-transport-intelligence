import { describe, expect, it } from 'vitest';

import { delayClass } from '@/lib/delay';
import {
  formatCompact,
  formatCoordinates,
  formatCount,
  formatCurrency,
  formatDelaySeconds,
  formatDuration,
  formatEta,
  formatIsoDuration,
  formatPassengerDelay,
  formatPercent,
  formatPercentWhole,
  formatSpeed,
  formatZScore,
} from '@/lib/format';

describe('DS-04 delayClass', () => {
  it.each([
    [-301, 'early'],
    [-300, 'on-time'],
    [0, 'on-time'],
    [300, 'on-time'],
    [301, 'late'],
    [600, 'late'],
    [601, 'very-late'],
    [null, 'unknown'],
    [undefined, 'unknown'],
  ] as const)('%s s is %s', (delay, expected) => {
    expect(delayClass(delay)).toBe(expected);
  });
});

describe('numbers (DOC-37 §4.1)', () => {
  it('groups, compacts and rounds', () => {
    expect(formatCount(12345)).toBe('12,345');
    expect(formatCompact(9999)).toBe('9,999');
    expect(formatCompact(412_345)).toBe('412.3K');
    expect(formatCompact(1_234_567)).toBe('1.2M');
    expect(formatPercent(0.784)).toBe('78.4%');
    expect(formatPercentWhole(0.82)).toBe('82%');
    expect(formatCurrency(50)).toBe('$50.00');
    expect(formatZScore(3.984)).toBe('z = 3.98');
    expect(formatCoordinates(44.94812, -93.278)).toBe('44.94812, −93.27800');
    expect(formatSpeed(7.6)).toBe('17 mph');
  });
});

describe('CP-06 formatDuration', () => {
  it.each([
    [384, '384 ms'],
    [4200, '4.2 s'],
    [4000, '4 s'],
    [59_960, '1 min'],
    [252_000, '4 min 12 s'],
    [240_000, '4 min'],
    [3_900_000, '1 h 5 min'],
    [3_600_000, '1 h'],
  ])('%i ms is "%s"', (ms, text) => {
    expect(formatDuration(ms)).toBe(text);
  });

  it('reads ISO-8601 durations of the API', () => {
    expect(formatIsoDuration('PT20M')).toBe('20 min');
    expect(formatIsoDuration('PT1H30M')).toBe('1 h 30 min');
    expect(formatIsoDuration('soon')).toBe('soon');
  });

  it('writes signed delays for ops tables', () => {
    expect(formatDelaySeconds(213)).toBe('+3 min 33 s');
    expect(formatDelaySeconds(-45)).toBe('−45 s');
    expect(formatDelaySeconds(120)).toBe('+2 min');
  });
});

describe('CP-04 passenger ETA and delay (DOC-37 §4.4)', () => {
  const now = Date.parse('2026-09-29T21:00:00Z');
  const inSeconds = (s: number) => new Date(now + s * 1000).toISOString();

  it.each([
    [45, 'Due'],
    [60, 'Due'],
    [61, '1 min'],
    [59 * 60 + 59, '59 min'],
    [60 * 60, '5:00 PM'],
  ])('an arrival in %i s reads "%s"', (seconds, text) => {
    expect(formatEta(inSeconds(seconds), now, 'America/Chicago')).toBe(text);
  });

  it.each([
    [59, 'On time'],
    [90, '2 min late'],
    [-150, '3 min early'],
    [-59, 'On time'],
  ])('a delay of %i s reads "%s"', (seconds, text) => {
    expect(formatPassengerDelay(seconds)).toBe(text);
  });
});
