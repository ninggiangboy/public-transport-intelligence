import type { components } from '@/api/generated/schema';
import { addDays } from '@/lib/time';

// Numbers of the Overview (DOC-36 screens/overview §4).

type OtpItem = components['schemas']['Item'];
type LiveVehicle = components['schemas']['LiveVehicleResponse'];
type JobSummary = components['schemas']['JobSummaryResponse'];

import type { Period } from '@/features/overview/search';

export { PERIODS, type Period } from '@/features/overview/search';
const DAYS: Record<Period, number> = { '1d': 1, '7d': 7, '30d': 30 };

export interface DateRange {
  from: string;
  to: string;
}

/**
 * The period ending yesterday (OTP is computed each night, DOC-23 §8), the one before it, and the range the chart
 * draws: `1d` shows the last 7 days so that the line has a shape.
 */
export function periodRanges(period: Period, today: string) {
  const to = addDays(today, -1);
  const days = DAYS[period];
  const range = (end: string, length: number): DateRange => ({ from: addDays(end, 1 - length), to: end });
  const chartDays = Math.max(days, 7);
  const current = range(to, days);
  const chart = range(to, chartDays);
  return {
    current,
    previous: range(addDays(current.from, -1), days),
    chart,
    chartPrevious: range(addDays(chart.from, -1), chartDays),
  };
}

/** The routes with the lowest OTP of the period. */
export function routesToWatch(items: readonly OtpItem[], count = 5): OtpItem[] {
  return [...items]
    .filter((item) => item.observationCount > 0)
    .sort((a, b) => a.otpPercentage - b.otpPercentage)
    .slice(0, count);
}

/**
 * Up to six routes for the network pulse: routes with bunching or an open disruption first, then the routes with the
 * most vehicles (§4.2).
 */
export function pulseRoutes(vehicles: readonly LiveVehicle[], disrupted: readonly string[], max = 6): string[] {
  const counts = new Map<string, number>();
  for (const vehicle of vehicles) counts.set(vehicle.routeId, (counts.get(vehicle.routeId) ?? 0) + 1);
  const bunched = new Set(vehicles.filter((vehicle) => vehicle.bunching).map((vehicle) => vehicle.routeId));
  const urgent = [...new Set([...bunched, ...disrupted.filter((routeId) => counts.has(routeId))])];
  const busiest = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([id]) => id);
  return [...new Set([...urgent, ...busiest])].slice(0, max);
}

/** Messages per second of each source over the newest whole minute of an E-31 summary by minute. */
export function ratesBySource(summary: JobSummary): Map<string, number> {
  const rates = new Map<string, number>();
  for (const series of summary.stream) {
    const points = [...series.points].sort((a, b) => a.bucketStart.localeCompare(b.bucketStart));
    // The newest bucket is still filling.
    const whole = points.length > 1 ? points[points.length - 2] : points[0];
    if (whole) rates.set(series.source, whole.read / 60);
  }
  return rates;
}
