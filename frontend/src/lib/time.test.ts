import { describe, expect, it } from 'vitest';

import {
  addDays,
  formatDate,
  formatDateRange,
  formatDateTime,
  formatHourOfDay,
  formatIsoUtc,
  formatRelative,
  formatServiceDate,
  formatTime,
  formatTimeRange,
  formatWeekday,
  formatWeekdayLong,
  fromLocalInput,
  isSameDay,
  parseIsoDuration,
  presetSeconds,
  relativeRefreshMs,
  toLocalInput,
  zoneAbbreviation,
  zonedDate,
  zonedWeekdayHour,
} from '@/lib/time';

const CHICAGO = 'America/Chicago';
// 4:05:12 PM CDT on 2026-09-29.
const AT = '2026-09-29T21:05:12Z';
const NOW = Date.parse('2026-09-29T21:10:00Z');

describe('formatRelative (CP-03, DOC-37 §4.3)', () => {
  const now = Date.parse('2026-09-29T21:10:00Z');
  const ago = (seconds: number) => new Date(now - seconds * 1000).toISOString();

  it.each([
    [4, 'just now'],
    [5, '5 s ago'],
    [59, '59 s ago'],
    [60, '1 min ago'],
    [59 * 60, '59 min ago'],
    [60 * 60, '1 h ago'],
    [23 * 3600 + 59 * 60, '23 h ago'],
  ])('%i s in the past reads "%s"', (seconds, text) => {
    expect(formatRelative(ago(seconds), now, CHICAGO)).toBe(text);
  });

  it('switches to the absolute date and time from 24 hours on', () => {
    expect(formatRelative(ago(24 * 3600), now, CHICAGO)).toBe('Sep 28, 4:10 PM CDT');
  });

  it('counts down into the future', () => {
    const at = new Date(now + 30_000).toISOString();
    expect(formatRelative(at, now, CHICAGO)).toBe('in 30 s');
    expect(formatRelative(new Date(now + 2000).toISOString(), now, CHICAGO)).toBe('now');
    expect(formatRelative(new Date(now + 5 * 60_000).toISOString(), now, CHICAGO)).toBe('in 5 min');
  });

  it('UX-05 measures a vehicle position against businessNow, 13 hours behind the machine clock', () => {
    const machineNow = Date.parse('2026-09-29T21:10:00Z');
    const businessNow = machineNow - 13 * 3600_000;
    const position = new Date(businessNow - 5000).toISOString();
    expect(formatRelative(position, businessNow, CHICAGO)).toBe('5 s ago');
    // Against the machine clock the same position would read as hours old.
    expect(formatRelative(position, machineNow, CHICAGO)).toBe('13 h ago');
  });
});

describe('absolute formats (DOC-37 §4.2)', () => {
  it('formats times with and without the zone and seconds', () => {
    expect(formatTime(AT, { timeZone: CHICAGO, showZone: false })).toBe('4:05 PM');
    expect(formatTime(AT, { timeZone: CHICAGO })).toBe('4:05 PM CDT');
    expect(formatTime(AT, { timeZone: CHICAGO, seconds: true })).toBe('4:05:12 PM CDT');
  });

  it('formats date and time, adding the year only in another year', () => {
    expect(formatDateTime(AT, { timeZone: CHICAGO, now: NOW })).toBe('Sep 29, 4:05 PM CDT');
    expect(formatDateTime('2025-09-29T21:05:00Z', { timeZone: CHICAGO, now: NOW })).toBe('Sep 29, 2025, 4:05 PM CDT');
  });

  it('formats dates, service dates and ranges', () => {
    expect(formatDate(AT, CHICAGO)).toBe('Sep 29, 2026');
    expect(formatServiceDate('2026-09-29')).toBe('Tue, Sep 29');
    expect(formatDateRange('2026-09-22T12:00:00Z', '2026-09-28T12:00:00Z', CHICAGO)).toBe('Sep 22 – Sep 28');
    expect(formatTimeRange('2026-09-30T01:00:00Z', '2026-09-30T02:15:00Z', CHICAGO)).toBe('8:00 PM – 9:15 PM CDT');
  });

  it('formats the heatmap axes', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map(formatWeekday)).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
    expect([0, 1, 12, 13, 23].map(formatHourOfDay)).toEqual(['12 AM', '1 AM', '12 PM', '1 PM', '11 PM']);
  });

  it('writes the ISO UTC instant used as the Timestamp title', () => {
    expect(formatIsoUtc(AT)).toBe('2026-09-29T21:05:12Z');
    expect(formatIsoUtc(Date.parse('2026-09-29T21:05:12.345Z'))).toBe('2026-09-29T21:05:12Z');
  });

  it('CP-05 changes the abbreviation when daylight saving time ends', () => {
    expect(zoneAbbreviation('2026-11-01T06:59:00Z', CHICAGO)).toBe('CDT');
    expect(zoneAbbreviation('2026-11-01T07:00:00Z', CHICAGO)).toBe('CST');
    expect(formatTime('2026-11-01T06:30:00Z', { timeZone: CHICAGO })).toBe('1:30 AM CDT');
    expect(formatTime('2026-11-01T07:30:00Z', { timeZone: CHICAGO })).toBe('1:30 AM CST');
  });

  it('tells whether two instants share a calendar day in the zone', () => {
    expect(isSameDay('2026-09-30T03:00:00Z', '2026-09-29T21:00:00Z', CHICAGO)).toBe(true);
    expect(isSameDay('2026-09-30T06:00:00Z', '2026-09-29T21:00:00Z', CHICAGO)).toBe(false);
  });
});

describe('relativeRefreshMs (DOC-35 §5.2)', () => {
  it('refreshes every second under a minute and every 30 s after', () => {
    expect(relativeRefreshMs(NOW - 10_000, NOW)).toBe(1000);
    expect(relativeRefreshMs(NOW - 59_999, NOW)).toBe(1000);
    expect(relativeRefreshMs(NOW - 60_000, NOW)).toBe(30_000);
  });
});

describe('parseIsoDuration', () => {
  it.each([
    ['PT20M', 20 * 60_000],
    ['PT1H30M', 90 * 60_000],
    ['PT45S', 45_000],
    ['PT0S', 0],
    ['P1DT2H', 26 * 3600_000],
    ['PT1.5S', 1500],
  ])('%s', (iso, ms) => {
    expect(parseIsoDuration(iso)).toBe(ms);
  });

  it.each(['', 'P', 'PT', '20M', 'PT20', 'banana'])('rejects "%s"', (iso) => {
    expect(parseIsoDuration(iso)).toBeNaN();
  });
});

describe('local input in a zone', () => {
  it('round-trips a wall-clock time through the agency zone', () => {
    expect(toLocalInput(AT, CHICAGO)).toBe('2026-09-29T16:05');
    expect(new Date(fromLocalInput('2026-09-29T16:05', CHICAGO)).toISOString()).toBe('2026-09-29T21:05:00.000Z');
    expect(new Date(fromLocalInput('2026-12-01T09:00', CHICAGO)).toISOString()).toBe('2026-12-01T15:00:00.000Z');
  });

  it('rejects malformed input', () => {
    expect(fromLocalInput('2026-09-29', CHICAGO)).toBeNaN();
  });
});

describe('presetSeconds', () => {
  it('reads minutes, hours and days', () => {
    expect(presetSeconds('15m')).toBe(900);
    expect(presetSeconds('6h')).toBe(21_600);
    expect(presetSeconds('7d')).toBe(604_800);
    expect(presetSeconds('soon')).toBeNaN();
  });
});

describe('zonedWeekdayHour', () => {
  it('reads the weekday and hour in the agency zone, not UTC', () => {
    // Tuesday 2026-09-29 21:19 UTC is Tuesday 4 PM in Chicago; 03:00 UTC on Wednesday is still Tuesday 10 PM there.
    expect(zonedWeekdayHour('2026-09-29T21:19:35Z', 'America/Chicago')).toEqual({ dayOfWeek: 2, hourOfDay: 16 });
    expect(zonedWeekdayHour('2026-09-30T03:00:00Z', 'America/Chicago')).toEqual({ dayOfWeek: 2, hourOfDay: 22 });
    expect(zonedWeekdayHour('2026-10-04T05:30:00Z', 'America/Chicago')).toEqual({ dayOfWeek: 7, hourOfDay: 0 });
  });

  it('names ISO weekdays', () => {
    expect(formatWeekdayLong(1)).toBe('Monday');
    expect(formatWeekdayLong(7)).toBe('Sunday');
  });
});

describe('service dates', () => {
  it('reads the date in the agency zone', () => {
    expect(zonedDate('2026-09-30T03:00:00Z', 'America/Chicago')).toBe('2026-09-29');
    expect(zonedDate('2026-09-30T06:00:00Z', 'America/Chicago')).toBe('2026-09-30');
  });

  it('moves calendar dates across months and years', () => {
    expect(addDays('2026-09-29', -1)).toBe('2026-09-28');
    expect(addDays('2026-10-01', -1)).toBe('2026-09-30');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
  });
});
