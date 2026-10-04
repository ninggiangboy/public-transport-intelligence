import type { Freshness } from '@/app/freshness';
import { pollSeconds } from '@/app/shell/link-status';
import type { RealtimeState } from '@/realtime/useRealtime';

type LiveSource = 'GTFS_RT_VEHICLE_POSITION' | 'GTFS_RT_TRIP_UPDATE';

/** The one condition the StaleBanner shows, highest priority first (DOC-37 §2.4). */
export type BannerCondition =
  | { kind: 'offline'; lastDataAt?: number }
  | { kind: 'freshnessUnknown' }
  | { kind: 'stale'; source: LiveSource; ageSeconds: number }
  | { kind: 'staleNoData' }
  | { kind: 'polling'; seconds: number }
  | { kind: 'reconnecting' };

/** The stream has been reconnecting this long before the banner mentions it. */
export const RECONNECTING_BANNER_AFTER_MS = 5_000;

interface Inputs {
  online: boolean;
  freshness: Freshness;
  realtime: RealtimeState;
  /** `reconnecting` for more than {@link RECONNECTING_BANNER_AFTER_MS}. */
  reconnectingLong: boolean;
}

export function bannerCondition({
  online,
  freshness,
  realtime,
  reconnectingLong,
}: Inputs): BannerCondition | undefined {
  if (!online) {
    const times = [realtime.lastEventAt?.getTime(), freshness.updatedAt].filter((t): t is number => t !== undefined);
    return { kind: 'offline', lastDataAt: times.length > 0 ? Math.max(...times) : undefined };
  }
  if (freshness.failed || freshness.data?.probeError) return { kind: 'freshnessUnknown' };
  if (freshness.data?.stale) {
    const live = freshness.data.sources.filter(
      (source): source is typeof source & { source: LiveSource } =>
        source.source === 'GTFS_RT_VEHICLE_POSITION' || source.source === 'GTFS_RT_TRIP_UPDATE',
    );
    const vehicles = live.find((source) => source.source === 'GTFS_RT_VEHICLE_POSITION');
    if (!vehicles?.lastEventAt) return { kind: 'staleNoData' };
    // The older of the two sources tells how far behind the live data is.
    const oldest = live
      .filter((source) => source.lastEventAt !== undefined && source.ageSeconds !== undefined)
      .reduce((a, b) => ((b.ageSeconds ?? 0) > (a.ageSeconds ?? 0) ? b : a), vehicles);
    return { kind: 'stale', source: oldest.source, ageSeconds: oldest.ageSeconds ?? 0 };
  }
  if (realtime.status === 'polling') return { kind: 'polling', seconds: pollSeconds(realtime) };
  if (realtime.status === 'reconnecting' && reconnectingLong) return { kind: 'reconnecting' };
  return undefined;
}
