import { useQuery } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { useFreshness } from '@/app/freshness';
import { BARS, gtfsRtPerMinute, LIVE_FEED_SUMMARY, messagesPerSecond } from '@/app/shell/feed-rate';
import { LINK_TONE, linkStatus } from '@/app/shell/link-status';
import { StatusDot } from '@/app/shell/RealtimeStatusDot';
import { en } from '@/i18n/en';
import { useOnline } from '@/lib/browser';
import { formatCount } from '@/lib/format';
import { useRelative } from '@/lib/use-now';
import { cn } from '@/lib/utils';
import type { RealtimeState } from '@/realtime/useRealtime';

const MINUTE_MS = 60_000;
const BAR_MAX_PX = 22;

function Updated({ at }: { at: string }) {
  return <>{en.time.updated(useRelative(at, 'event'))}</>;
}

interface LiveFeedCardProps {
  realtime: RealtimeState;
  /** Viewer and above: msg/s and the 24-minute sparkline (E-31). */
  showRate: boolean;
  /** Icon rail of the collapsed sidebar: the dot only. */
  collapsed?: boolean;
}

/** "Live feed" at the foot of the sidebar: stream state, age of the newest vehicle position and the feed rate. */
export function LiveFeedCard({ realtime, showRate, collapsed = false }: LiveFeedCardProps) {
  const online = useOnline();
  const { data: freshness } = useFreshness();
  const vehicles = freshness?.sources.find((source) => source.source === 'GTFS_RT_VEHICLE_POSITION');
  const status = linkStatus(realtime, online);
  const tone = status === 'live' && (freshness?.stale ?? false) ? 'warning' : LINK_TONE[status];

  const summary = useQuery({
    queryKey: keys.etl.jobs.summary(LIVE_FEED_SUMMARY),
    queryFn: () => {
      const to = Date.now();
      return read(
        api.GET('/api/v1/etl/jobs/summary', {
          params: {
            query: {
              from: new Date(to - BARS * MINUTE_MS).toISOString(),
              to: new Date(to).toISOString(),
              bucket: LIVE_FEED_SUMMARY.bucket,
            },
          },
        }),
      );
    },
    enabled: showRate,
    select: ({ data }) => gtfsRtPerMinute(data),
  });
  const perMinute = showRate ? summary.data : undefined;
  const rate = perMinute ? messagesPerSecond(perMinute) : undefined;
  const peak = Math.max(1, ...(perMinute ?? []));

  if (collapsed) {
    return (
      <div className="flex justify-center py-2" title={en.liveFeed.title}>
        <StatusDot tone={tone} />
      </div>
    );
  }

  return (
    <section
      aria-label={en.liveFeed.title}
      className="rounded-lg border border-border bg-card px-3 py-2.75 shadow-xs"
      data-testid="live-feed"
    >
      <div className="flex items-center gap-2 text-label font-medium text-foreground">
        <StatusDot tone={tone} />
        {en.liveFeed.title}
      </div>
      <p className="mt-1 text-xs text-muted-foreground tabular-nums">
        {vehicles?.lastEventAt ? <Updated at={vehicles.lastEventAt} /> : en.freshness.noData}
        {rate === undefined ? null : (
          <>
            {' · '}
            <span className="font-medium text-foreground-2">{en.liveFeed.rate(formatCount(Math.round(rate)))}</span>
          </>
        )}
      </p>
      {perMinute && perMinute.length > 0 ? (
        <div role="img" aria-label={en.liveFeed.rateChart} className="mt-2.25 flex h-5.5 items-end gap-0.5">
          {perMinute.map((value, index) => (
            <span
              key={index}
              className={cn(
                'flex-1 rounded-[1.5px]',
                index === perMinute.length - 1 ? 'bg-tone-success-solid' : 'bg-tone-success-border',
              )}
              style={{ height: `${Math.max(2, Math.round((value / peak) * BAR_MAX_PX))}px` }}
            />
          ))}
        </div>
      ) : null}
    </section>
  );
}
