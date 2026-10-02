// Number, duration, ETA and delay formatting of DOC-37 §4.1, §4.4 and §4.5: locale en-US, cached Intl formatters.
import { en } from '@/i18n/en';
import { parseIsoDuration, toMillis, formatTime, type Instant } from '@/lib/time';

const LOCALE = 'en-US';
const MINUS = '−';

const COUNT = new Intl.NumberFormat(LOCALE);
const COMPACT = new Intl.NumberFormat(LOCALE, { notation: 'compact', maximumFractionDigits: 1 });
const PERCENT_1 = new Intl.NumberFormat(LOCALE, {
  style: 'percent',
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});
const PERCENT_0 = new Intl.NumberFormat(LOCALE, { style: 'percent', maximumFractionDigits: 0 });
const USD = new Intl.NumberFormat(LOCALE, { style: 'currency', currency: 'USD' });
const FIXED_2 = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const FIXED_5 = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 5, maximumFractionDigits: 5 });
const DECIMAL_1 = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 0, maximumFractionDigits: 1 });
const INTEGER = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });

/** The typographic minus of the copy deck instead of the hyphen. */
function signed(text: string): string {
  return text.replace('-', MINUS);
}

/** "12,345". */
export function formatCount(value: number) {
  return COUNT.format(value);
}

/** "12,345" below 10,000, "412K" and "1.2M" from there on (summary cards, chart axes). */
export function formatCompact(value: number) {
  return Math.abs(value) >= 10_000 ? COMPACT.format(value) : COUNT.format(value);
}

/** "78.4%" for a ratio in [0, 1]. */
export function formatPercent(ratio: number) {
  return PERCENT_1.format(ratio);
}

/** "82%": AI confidence and ratios such as `refundRatio`. */
export function formatPercentWhole(ratio: number) {
  return PERCENT_0.format(ratio);
}

/** "$50.00". */
export function formatCurrency(amount: number) {
  return USD.format(amount);
}

/** "z = 3.98". */
export function formatZScore(z: number) {
  return en.format.zScore(signed(FIXED_2.format(z)));
}

/** "44.94812, −93.27800". */
export function formatCoordinates(lat: number, lon: number) {
  return `${signed(FIXED_5.format(lat))}, ${signed(FIXED_5.format(lon))}`;
}

const MPS_TO_MPH = 2.23694;

/** "17 mph" from metres per second. */
export function formatSpeed(metersPerSecond: number) {
  return en.format.mph(INTEGER.format(metersPerSecond * MPS_TO_MPH));
}

// ---------------------------------------------------------------------------------------------------------------------
// Durations (DOC-37 §4.5)

/** "384 ms", "4.2 s", "4 min 12 s", "1 h 5 min". */
export function formatDuration(ms: number): string {
  const u = en.format.unit;
  if (ms < 1000) return `${INTEGER.format(ms)} ${u.ms}`;
  const tenths = Math.round(ms / 100) / 10;
  if (tenths < 60) return `${DECIMAL_1.format(tenths)} ${u.s}`;
  const totalSeconds = Math.round(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return minutes > 0 ? `${hours} ${u.h} ${minutes} ${u.min}` : `${hours} ${u.h}`;
  return seconds > 0 ? `${minutes} ${u.min} ${seconds} ${u.s}` : `${minutes} ${u.min}`;
}

/** "20 min", "1 h 30 min" for an ISO-8601 duration from the API; the raw text when it cannot be parsed. */
export function formatIsoDuration(iso: string): string {
  const ms = parseIsoDuration(iso);
  return Number.isNaN(ms) ? iso : formatDuration(ms);
}

/** "+3 min 33 s", "−45 s": a signed delay in seconds, for ops tables. */
export function formatDelaySeconds(seconds: number): string {
  const u = en.format.unit;
  const sign = seconds < 0 ? MINUS : '+';
  const abs = Math.round(Math.abs(seconds));
  if (abs < 60) return `${sign}${abs} ${u.s}`;
  const minutes = Math.floor(abs / 60);
  const rest = abs % 60;
  return rest > 0 ? `${sign}${minutes} ${u.min} ${rest} ${u.s}` : `${sign}${minutes} ${u.min}`;
}

// ---------------------------------------------------------------------------------------------------------------------
// Passenger ETA and delay (DOC-37 §4.4)

/**
 * "Due" up to 60 s, then "{n} min" rounded down, and from 60 min on the absolute time ("5:42 PM").
 * `now` is `businessNow` (DOC-34 §8).
 */
export function formatEta(eta: Instant, now: number, timeZone?: string): string {
  const remaining = (toMillis(eta) - now) / 1000;
  if (remaining <= 60) return en.format.due;
  if (remaining < 3600) return `${Math.floor(remaining / 60)} ${en.format.unit.min}`;
  return formatTime(eta, { showZone: false, ...(timeZone ? { timeZone } : {}) });
}

/** "On time" under 60 s, else "{n} min late" or "{n} min early" rounded to the nearest minute. */
export function formatPassengerDelay(delaySeconds: number): string {
  if (Math.abs(delaySeconds) < 60) return en.delay.class['on-time'];
  const minutes = Math.round(Math.abs(delaySeconds) / 60);
  return delaySeconds > 0 ? en.format.minLate(minutes) : en.format.minEarly(minutes);
}
