import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { BellRing } from 'lucide-react';

import { ErrorState } from '@/components/ErrorState';
import { LineStrip } from '@/components/LineStrip';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { Button } from '@/components/ui/button';
import { Metric, Metrics, PanelFooter, PanelNotice, PanelSection } from '@/features/map/components/panel-parts';
import { PairSuggestion } from '@/features/map/components/VehiclePanel';
import { directionName, orderedStops, routeName, stopsAround, type RouteItem } from '@/features/map/model';
import { bunchingDetailQuery, routeDetailQuery } from '@/features/map/queries';
import { alertsCopy } from '@/i18n/alerts';
import { mapCopy } from '@/i18n/map';
import { formatGap } from '@/lib/alert-display';
import { useBusinessClock } from '@/lib/business-clock';
import { formatTime } from '@/lib/time';
import { useRelative } from '@/lib/use-now';

const copy = mapCopy.map.bunching;

function isNotFound(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 404;
}

interface BunchingPanelProps {
  episodeId: string;
  routes: ReadonlyMap<string, RouteItem>;
  operator: boolean;
  /** The alert of the episode, for "Open alert"; absent when it is not among the open alerts. */
  alertId: string | undefined;
}

/** A bunching pair (screens/live-map §4.1 `BunchingPanel`, viewer and above). */
export function BunchingPanel({ episodeId, routes, operator, alertId }: BunchingPanelProps) {
  const clock = useBusinessClock();
  const query = useQuery(bunchingDetailQuery(episodeId));
  const episode = query.data?.data;
  const detail = useQuery({ ...routeDetailQuery(episode?.routeId ?? ''), enabled: episode !== undefined });
  const ended = useRelative(episode?.episodeEnd ?? new Date(0).toISOString(), 'event');

  if (query.isError && isNotFound(query.error)) return <PanelNotice>{mapCopy.map.panel.gone.bunching}</PanelNotice>;
  if (query.isError && !episode)
    return (
      <div className="p-4">
        <ErrorState
          error={query.error}
          variant="block"
          panel={mapCopy.map.panel.failed}
          onRetry={() => void query.refetch()}
        />
      </div>
    );
  if (!episode) return <PanelSkeleton variant="detail" />;

  const route = routes.get(episode.routeId);
  const direction = detail.data?.data.directions.find((d) => d.directionId === episode.directionId);
  const stops = orderedStops(direction);
  const openStop = stops.find((stop) => stop.stopId === episode.openStopId);
  const detected = formatTime(episode.episodeStart, { timeZone: clock.timezone, showZone: false });
  const directionLabel = directionName(direction, episode.directionId);
  const strip = episode.openStopId ? stopsAround(stops, [episode.openStopId], 2) : [];
  const buses = copy.buses(episode.vehicleLeader, episode.vehicleFollower);
  const reason = episode.closeReason ? (alertsCopy.closeReason[episode.closeReason] ?? episode.closeReason) : '';

  return (
    <>
      <div className="px-[18px] pt-3.5 pb-4">
        <h2 className="text-[17px] leading-tight font-semibold tracking-[-0.022em]">
          {copy.title(routeName(episode.routeId, routes))}
        </h2>
        <p className="mt-1 text-[12.5px] text-muted-foreground">
          {openStop ? copy.where(directionLabel, openStop.name, detected) : copy.detected(directionLabel, detected)}
        </p>
        {episode.episodeEnd ? (
          <p className="mt-2 text-xs font-medium text-tone-success-fg">{copy.ended(ended, reason)}</p>
        ) : null}
      </div>
      <Metrics>
        <Metric label={copy.headwayNow} value={formatGap(episode.lastGapSeconds)} tone="warning" />
        <Metric label={copy.scheduled} value={formatGap(episode.scheduledHeadwaySeconds)} />
        <Metric label={copy.smallestGap} value={formatGap(episode.minGapSeconds)} />
      </Metrics>
      <PanelSection aside={<span className="text-xs text-muted-foreground">{buses}</span>}>
        {strip.length > 0 && episode.openStopId ? (
          <LineStrip
            orientation="horizontal"
            {...(route?.color ? { color: route.color } : {})}
            stops={strip.map((stop) => ({ id: stop.stopId, name: stop.name }))}
            marker={{ atStopId: episode.openStopId, label: buses }}
          />
        ) : null}
      </PanelSection>
      <PanelSection>
        <PairSuggestion episodeId={episode.id} operator={operator} />
      </PanelSection>
      {alertId ? (
        <PanelFooter>
          <Button variant="outline" size="sm" asChild>
            <Link to="/alerts" search={{ alert: alertId }}>
              <BellRing aria-hidden="true" />
              {mapCopy.map.panel.openAlert}
            </Link>
          </Button>
        </PanelFooter>
      ) : null}
    </>
  );
}
