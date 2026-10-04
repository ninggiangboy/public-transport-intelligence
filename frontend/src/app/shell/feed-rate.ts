import type { components } from '@/api/generated/schema';

type JobSummary = components['schemas']['JobSummaryResponse'];

export const BARS = 24;

/** E-31 for the last 24 minutes, per minute: msg/s and the sparkline of the card (DOC-34 §4.2). */
export const LIVE_FEED_SUMMARY = { window: '24m', bucket: '1m' } as const;

/** Messages read per minute from the two GTFS-realtime sources, oldest first. */
export function gtfsRtPerMinute(summary: JobSummary): number[] {
  const perBucket = new Map<string, number>();
  for (const series of summary.stream) {
    if (!series.source.startsWith('GTFS_RT')) continue;
    for (const point of series.points) {
      perBucket.set(point.bucketStart, (perBucket.get(point.bucketStart) ?? 0) + point.read);
    }
  }
  return [...perBucket.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([, read]) => read)
    .slice(-BARS);
}

/** The newest whole minute: the last bucket is still filling. */
export function messagesPerSecond(perMinute: number[]): number | undefined {
  const complete = perMinute.length > 1 ? perMinute[perMinute.length - 2] : perMinute[0];
  return complete === undefined ? undefined : complete / 60;
}
