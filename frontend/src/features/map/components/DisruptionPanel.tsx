import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { BellRing } from 'lucide-react';
import { useEffect } from 'react';

import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { ErrorState } from '@/components/ErrorState';
import { LineStrip } from '@/components/LineStrip';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SeverityBadge } from '@/components/SeverityBadge';
import { Button } from '@/components/ui/button';
import { Metric, Metrics, PanelFooter, PanelNotice, PanelSection } from '@/features/map/components/panel-parts';
import { directionName, orderedStops, routeName, stopsAround, type RouteItem } from '@/features/map/model';
import { disruptionDetailQuery, routeDetailQuery } from '@/features/map/queries';
import { alertsCopy } from '@/i18n/alerts';
import { mapCopy } from '@/i18n/map';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDelaySeconds, formatPercentWhole, formatZScore } from '@/lib/format';
import { formatTime } from '@/lib/time';

const copy = mapCopy.map.disruption;

/** A disruption that ended stays on screen this long with "back to normal", then closes (screens/live-map §6). */
export const ENDED_PANEL_MS = 2 * 60_000;

function isNotFound(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 404;
}

interface DisruptionPanelProps {
  episodeId: string;
  routes: ReadonlyMap<string, RouteItem>;
  /** Viewer and above: z-scores, the baseline and the AI analysis. */
  staff: boolean;
  alertId: string | undefined;
  /** Called 2 minutes after the episode ended. */
  onExpired: () => void;
}

/** A disruption episode (screens/live-map §4.1 `DisruptionPanel`). */
export function DisruptionPanel({ episodeId, routes, staff, alertId, onExpired }: DisruptionPanelProps) {
  const clock = useBusinessClock();
  const query = useQuery(disruptionDetailQuery(episodeId));
  const episode = query.data?.data;
  const detail = useQuery({ ...routeDetailQuery(episode?.routeId ?? ''), enabled: episode !== undefined });
  const ended = episode !== undefined && (episode.episodeEnd !== undefined || episode.status === 'CLOSED');

  useEffect(() => {
    if (!ended) return;
    const timer = setTimeout(onExpired, ENDED_PANEL_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [ended, onExpired]);

  if (query.isError && isNotFound(query.error)) return <PanelNotice>{mapCopy.map.panel.gone.disruption}</PanelNotice>;
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
  const name = routeName(episode.routeId, routes);
  const direction = detail.data?.data.directions.find((d) => d.directionId === episode.directionId);
  const stops = orderedStops(direction);
  const affected = episode.affectedStopIds;
  const affectedStops = stops.filter((stop) => affected.includes(stop.stopId));
  const first = affectedStops[0];
  const last = affectedStops.at(-1);
  const strip = stopsAround(stops, affected, 1);
  const segments = strip.slice(0, -1).flatMap((stop, index) => {
    const next = strip[index + 1];
    return next && affected.includes(stop.stopId) && affected.includes(next.stopId)
      ? [{ from: stop.stopId, to: next.stopId, delayClass: 'very-late' as const }]
      : [];
  });
  const since = formatTime(episode.episodeStart, { timeZone: clock.timezone, showZone: false });
  const cause = episode.likelyCause;

  return (
    <>
      <div className="px-[18px] pt-3.5 pb-4">
        <h2 className="text-[17px] leading-tight font-semibold tracking-[-0.022em]">
          {copy.title(name, direction ? directionName(direction, episode.directionId).toLowerCase() : '')}
        </h2>
        <div className="mt-1.5 flex flex-wrap items-center gap-2 text-[12.5px] text-muted-foreground">
          {first && last ? <span>{copy.range(first.name, last.name)}</span> : null}
          <SeverityBadge severity={episode.severity as 0 | 1 | 2} size="sm" />
          <span>{copy.since(since)}</span>
        </div>
        {ended ? (
          <div className="mt-3">
            <Callout tone="success">{copy.backToNormal(name)}</Callout>
          </div>
        ) : null}
      </div>
      <Metrics>
        <Metric label={copy.avgDelay} value={formatDelaySeconds(episode.currentAvgDelaySeconds)} tone="danger" />
        <Metric label={copy.peakDelay} value={formatDelaySeconds(episode.peakAvgDelaySeconds)} />
        {staff && episode.peakZScore !== undefined ? (
          <Metric label={copy.peakZ} value={formatZScore(episode.peakZScore)} />
        ) : null}
      </Metrics>
      {staff && episode.baselineMeanSeconds !== undefined ? (
        <PanelSection title={copy.delayVsNormal}>
          <DelayVsNormal
            current={episode.currentAvgDelaySeconds}
            mean={episode.baselineMeanSeconds}
            stddev={episode.baselineStddevSeconds ?? 0}
          />
        </PanelSection>
      ) : null}
      {strip.length > 0 ? (
        <PanelSection
          aside={
            <span className="text-xs text-muted-foreground">{copy.stopsAffected(affected.length, stops.length)}</span>
          }
        >
          <LineStrip
            orientation="horizontal"
            {...(route?.color ? { color: route.color } : {})}
            stops={strip.map((stop) => ({ id: stop.stopId, name: stop.name }))}
            segments={segments}
          />
        </PanelSection>
      ) : null}
      {staff ? (
        <PanelSection>
          <Callout tone="primary" title={copy.likelyCause}>
            {cause ? (
              <div className="flex flex-col gap-1.5">
                <p className="flex flex-wrap items-center gap-2 text-foreground">
                  <strong>{alertsCopy.likelyCause[cause] ?? cause}</strong>
                  {episode.causeConfidence !== undefined ? <ConfidenceChip value={episode.causeConfidence} /> : null}
                </p>
                {episode.dataIssueProbability !== undefined ? (
                  <p>{copy.dataIssue(formatPercentWhole(episode.dataIssueProbability))}</p>
                ) : null}
                {episode.modelVersion ? <p className="font-mono text-xs">{episode.modelVersion}</p> : null}
              </div>
            ) : (
              <p>{copy.notClassified}</p>
            )}
          </Callout>
        </PanelSection>
      ) : null}
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

/** The current average delay against the normal band, mean ± one standard deviation. */
function DelayVsNormal({ current, mean, stddev }: { current: number; mean: number; stddev: number }) {
  const max = Math.max(current, mean + stddev, 1) * 1.1;
  const at = (value: number) => `${(Math.min(Math.max(value, 0), max) / max) * 100}%`;
  const normal = copy.normal(formatDelaySeconds(mean));
  return (
    <div className="flex flex-col gap-1.5">
      <div
        role="img"
        aria-label={`${copy.delayVsNormal}: ${formatDelaySeconds(current)}, ${normal}`}
        className="relative h-2.5 rounded-full bg-muted"
      >
        <span
          className="absolute inset-y-0 rounded-full bg-tone-neutral-border"
          style={{ left: at(mean - stddev), right: `calc(100% - ${at(mean + stddev)})` }}
        />
        <span className="absolute inset-y-0 left-0 rounded-full bg-tone-danger-solid" style={{ width: at(current) }} />
        <span className="absolute -inset-y-1 w-0.5 rounded-full bg-foreground" style={{ left: at(mean) }} />
      </div>
      <div className="flex justify-between text-xs text-muted-foreground tabular-nums">
        <span className="text-tone-danger-fg">{formatDelaySeconds(current)}</span>
        <span>{normal}</span>
      </div>
    </div>
  );
}
