// Date and time formatting of DOC-37 §4.2, §4.3 and DOC-34 §8: locale en-US, the agency time zone with its CDT/CST
// abbreviation, cached Intl formatters and no date library.

import { en } from '@/i18n/en';

const LOCALE = 'en-US';
const EN_DASH = '–';

/** An instant: ISO-8601 string with offset, or epoch milliseconds. */
export type Instant = string | number | Date;

/** The browser's zone, the fallback until a feed supplies the agency's (DOC-34 §8). */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = `${timeZone}|${JSON.stringify(options)}`;
  let cached = formatters.get(key);
  if (!cached) {
    cached = new Intl.DateTimeFormat(LOCALE, { timeZone, ...options });
    formatters.set(key, cached);
  }
  return cached;
}

/** ICU puts a narrow no-break space before AM/PM in newer versions; the copy deck uses a plain space. */
function plain(text: string): string {
  return text.replace(/[\u202f\u00a0]/g, ' ');
}

export function toMillis(at: Instant): number {
  return at instanceof Date ? at.getTime() : typeof at === 'number' ? at : Date.parse(at);
}

type TimeParts = Pick<Intl.DateTimeFormatOptions, 'hour' | 'minute' | 'second' | 'timeZoneName'>;

function timeOptions(seconds: boolean, zone: boolean): TimeParts {
  return {
    hour: 'numeric',
    minute: '2-digit',
    ...(seconds ? { second: '2-digit' as const } : {}),
    ...(zone ? { timeZoneName: 'short' as const } : {}),
  };
}

export interface ClockOptions {
  timeZone?: string;
  /** Append the zone abbreviation ("CDT"). Default true. */
  showZone?: boolean;
  /** Ops tables show seconds. */
  seconds?: boolean;
}

/** "4:05 PM", "4:05 PM CDT", "4:05:12 PM CDT" (DOC-37 §4.2). */
export function formatTime(
  at: Instant,
  { timeZone = browserTimeZone(), showZone = true, seconds = false }: ClockOptions = {},
) {
  return plain(formatter(timeZone, timeOptions(seconds, showZone)).format(toMillis(at)));
}

export interface DateTimeOptions extends ClockOptions {
  /** The current instant; a different calendar year than this one adds the year. */
  now?: number;
}

function calendarYear(ms: number, timeZone: string): string {
  return formatter(timeZone, { year: 'numeric' }).format(ms);
}

/** "Sep 29, 4:05 PM CDT", or "Sep 29, 2025, 4:05 PM CDT" in another year than `now`. */
export function formatDateTime(
  at: Instant,
  { timeZone = browserTimeZone(), showZone = true, seconds = false, now = Date.now() }: DateTimeOptions = {},
) {
  const ms = toMillis(at);
  const withYear = calendarYear(ms, timeZone) !== calendarYear(now, timeZone);
  return plain(
    formatter(timeZone, {
      ...(withYear ? { year: 'numeric' as const } : {}),
      month: 'short',
      day: 'numeric',
      ...timeOptions(seconds, showZone),
    }).format(ms),
  );
}

/** "Sep 29, 2026" (feeds, OTP). */
export function formatDate(at: Instant, timeZone = browserTimeZone()) {
  return plain(formatter(timeZone, { year: 'numeric', month: 'short', day: 'numeric' }).format(toMillis(at)));
}

/** "Tue, Sep 29" for a `serviceDate` ("2026-09-29"), which has no zone and must not shift. */
export function formatServiceDate(serviceDate: string) {
  const [year = 0, month = 1, day = 1] = serviceDate.split('-').map(Number);
  return plain(
    formatter('UTC', { weekday: 'short', month: 'short', day: 'numeric' }).format(Date.UTC(year, month - 1, day)),
  );
}

/** "Sep 22 – Sep 28" (scorecard). */
export function formatDateRange(from: Instant, to: Instant, timeZone = browserTimeZone()) {
  const short = formatter(timeZone, { month: 'short', day: 'numeric' });
  return `${plain(short.format(toMillis(from)))} ${EN_DASH} ${plain(short.format(toMillis(to)))}`;
}

/** "8:00 PM – 9:15 PM CDT": the zone is written once, after the end. */
export function formatTimeRange(from: Instant, to: Instant, timeZone = browserTimeZone()) {
  return `${formatTime(from, { timeZone, showZone: false })} ${EN_DASH} ${formatTime(to, { timeZone })}`;
}

/** "Mon" … "Sun" for a day index 0 (Monday) … 6 (heatmap axis). */
export function formatWeekday(index: number) {
  // 2024-01-01 was a Monday.
  return formatter('UTC', { weekday: 'short' }).format(Date.UTC(2024, 0, 1 + index));
}

/** "Monday" … "Sunday" for an ISO day of week 1 (Monday) … 7. */
export function formatWeekdayLong(isoDay: number) {
  return formatter('UTC', { weekday: 'long' }).format(Date.UTC(2024, 0, isoDay));
}

const ISO_WEEKDAY: Record<string, number> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

/** ISO day of week (1 = Monday) and hour 0..23 of an instant in a zone: the slot of the ETA table (DOC-23 §7). */
export function zonedWeekdayHour(at: Instant, timeZone = browserTimeZone()): { dayOfWeek: number; hourOfDay: number } {
  const parts = formatter(timeZone, { weekday: 'short', hour: 'numeric', hourCycle: 'h23' }).formatToParts(
    toMillis(at),
  );
  const weekday = parts.find((part) => part.type === 'weekday')?.value ?? 'Mon';
  const hour = Number(parts.find((part) => part.type === 'hour')?.value ?? 0);
  return { dayOfWeek: ISO_WEEKDAY[weekday] ?? 1, hourOfDay: hour % 24 };
}

/** "12 AM", "1 AM" … "11 PM" for an hour of the day 0..23 (heatmap axis). */
export function formatHourOfDay(hour: number) {
  return plain(formatter('UTC', { hour: 'numeric' }).format(Date.UTC(2024, 0, 1, hour)));
}

/** "2026-09-29T21:05:12Z": the title of every Timestamp, for matching against logs (DOC-37 §4.2). */
export function formatIsoUtc(at: Instant) {
  return new Date(toMillis(at)).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** "CDT" or "CST" for the instant (CP-05). */
export function zoneAbbreviation(at: Instant, timeZone = browserTimeZone()) {
  const part = formatter(timeZone, { timeZoneName: 'short' })
    .formatToParts(toMillis(at))
    .find((p) => p.type === 'timeZoneName');
  return part?.value ?? timeZone;
}

/** True when both instants fall on the same calendar day in the zone. */
export function isSameDay(a: Instant, b: Instant, timeZone = browserTimeZone()) {
  const day = formatter(timeZone, { year: 'numeric', month: '2-digit', day: '2-digit' });
  return day.format(toMillis(a)) === day.format(toMillis(b));
}

const UNIT_SECONDS = { m: 60, h: 3600, d: 86_400 } as const;

/** Seconds of a window preset such as "15m", "6h" or "7d"; NaN for anything else. */
export function presetSeconds(preset: string): number {
  const match = /^(\d+)([mhd])$/.exec(preset);
  return match ? Number(match[1]) * UNIT_SECONDS[match[2] as keyof typeof UNIT_SECONDS] : Number.NaN;
}

// ---------------------------------------------------------------------------------------------------------------------
// Wall-clock input in the agency zone (TimeRangePicker)

function zoneParts(ms: number, timeZone: string): Record<string, number> {
  const parts: Record<string, number> = {};
  for (const part of formatter(timeZone, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(ms)) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return parts;
}

/** "2026-09-29T16:05" as the value of a `datetime-local` input showing the instant in the zone. */
export function toLocalInput(at: Instant, timeZone = browserTimeZone()): string {
  const p = zoneParts(toMillis(at), timeZone);
  const two = (n: number | undefined) => String(n ?? 0).padStart(2, '0');
  return `${p.year}-${two(p.month)}-${two(p.day)}T${two(p.hour)}:${two(p.minute)}`;
}

/** The instant (epoch ms) at which the zone's wall clock reads `local` ("2026-09-29T16:05"); NaN when malformed. */
export function fromLocalInput(local: string, timeZone = browserTimeZone()): number {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local);
  if (!match) return Number.NaN;
  const [year, month, day, hour, minute] = match.slice(1).map(Number) as [number, number, number, number, number];
  const asUtc = Date.UTC(year, month - 1, day, hour, minute);
  // The zone's offset at that wall time; one correction step handles the hour around a DST change.
  const offsetAt = (ms: number) => {
    const p = zoneParts(ms, timeZone);
    return Date.UTC(p.year ?? 0, (p.month ?? 1) - 1, p.day ?? 1, p.hour ?? 0, p.minute ?? 0, p.second ?? 0) - ms;
  };
  const first = asUtc - offsetAt(asUtc);
  return asUtc - offsetAt(first);
}

// ---------------------------------------------------------------------------------------------------------------------
// Relative time (DOC-37 §4.3)

/**
 * "just now", "12 s ago", "in 30 s", "4 min ago", "3 h ago"; from 24 hours on, the absolute date and time.
 * `now` is `businessNow` on the event axis and the machine clock on the audit axis (DOC-34 §8).
 */
export function formatRelative(at: Instant, now: number, timeZone = browserTimeZone()) {
  const diffSeconds = (now - toMillis(at)) / 1000;
  const future = diffSeconds < 0;
  const age = Math.abs(diffSeconds);
  if (age < 5) return future ? en.time.now : en.time.justNow;
  if (age >= 24 * 3600) return formatDateTime(at, { timeZone, now });
  const unit = en.format.unit;
  const [n, label] =
    age < 60
      ? [Math.floor(age), unit.s]
      : age < 3600
        ? [Math.floor(age / 60), unit.min]
        : [Math.floor(age / 3600), unit.h];
  return future ? en.time.in(n, label) : en.time.ago(n, label);
}

/** The delay between two refreshes of a relative label: every second under a minute, then every 30 s (DOC-35 §5.2). */
export function relativeRefreshMs(at: Instant, now: number) {
  return Math.abs(now - toMillis(at)) < 60_000 ? 1_000 : 30_000;
}

// ---------------------------------------------------------------------------------------------------------------------
// ISO-8601 durations

const ISO_DURATION = /^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/;

/** Milliseconds of an ISO-8601 duration with days, hours, minutes and seconds ("PT20M", "PT1H30M"); NaN when invalid. */
export function parseIsoDuration(iso: string): number {
  const match = ISO_DURATION.exec(iso);
  if (!match || iso === 'P' || iso.endsWith('T')) return Number.NaN;
  const [days, hours, minutes, seconds] = [1, 2, 3, 4].map((group) => Number(match[group] ?? 0));
  return (((days ?? 0) * 24 + (hours ?? 0)) * 60 + (minutes ?? 0)) * 60_000 + (seconds ?? 0) * 1000;
}
