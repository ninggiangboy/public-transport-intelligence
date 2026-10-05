import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';

import type { components } from '@/api/generated/schema';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { DetailDrawer } from '@/components/DetailDrawer';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SeverityBadge } from '@/components/SeverityBadge';
import { ToneBadge } from '@/components/ToneBadge';
import { Button } from '@/components/ui/button';
import { directionName } from '@/features/scorecard/display';
import { disruptionDetailQuery } from '@/features/scorecard/queries';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { formatDelaySeconds, formatPercentWhole, formatZScore } from '@/lib/format';
import { formatDateTime } from '@/lib/time';

type RouteDetail = components['schemas']['RouteDetailResponse'];
type Disruption = components['schemas']['DisruptionResponse'];

const copy = scorecardCopy.scorecard;
const labels = copy.disruption;

function isNotFound(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 404;
}

function severityOf(episode: Disruption) {
  return episode.severity <= 0 ? 0 : episode.severity === 1 ? 1 : 2;
}

function Details({ episode, route, timezone }: { episode: Disruption; route: RouteDetail; timezone: string }) {
  const stops = route.directions.find((d) => d.directionId === episode.directionId)?.stops ?? [];
  const stopName = (id: string) => stops.find((stop) => stop.stopId === id)?.name ?? id;
  const when = (at: string) => formatDateTime(at, { timeZone: timezone });
  const cause = episode.likelyCause;
  const baseline =
    episode.baselineMeanSeconds === undefined
      ? en.kv.empty
      : episode.baselineStddevSeconds === undefined
        ? formatDelaySeconds(episode.baselineMeanSeconds)
        : `${formatDelaySeconds(episode.baselineMeanSeconds)} ± ${formatDelaySeconds(episode.baselineStddevSeconds).replace(/^\+/, '')}`;
  const z = [episode.currentZScore, episode.peakZScore]
    .map((value) => (value === undefined ? undefined : formatZScore(value)))
    .filter(Boolean)
    .join(' / ');
  return (
    <KeyValueList
      items={[
        {
          label: labels.time,
          value: `${when(episode.episodeStart)} – ${episode.episodeEnd ? when(episode.episodeEnd) : copy.disruptions.ongoing}`,
        },
        { label: labels.averageDelay, value: formatDelaySeconds(episode.currentAvgDelaySeconds) },
        { label: labels.peakDelay, value: formatDelaySeconds(episode.peakAvgDelaySeconds) },
        { label: labels.normal, value: baseline },
        { label: labels.zScore, value: z || en.kv.empty },
        {
          label: labels.affectedStops,
          value:
            episode.affectedStopIds.length === 0 ? (
              en.kv.empty
            ) : (
              <ul className="flex flex-col gap-0.5">
                {episode.affectedStopIds.map((id) => (
                  <li key={id}>{stopName(id)}</li>
                ))}
              </ul>
            ),
        },
        {
          label: labels.likelyCause,
          value: cause ? (
            <span className="inline-flex flex-wrap items-center gap-2">
              {alertsCopy.likelyCause[cause] ?? cause}
              {episode.causeConfidence !== undefined ? (
                <ConfidenceChip
                  value={episode.causeConfidence}
                  {...(episode.modelVersion ? { modelVersion: episode.modelVersion } : {})}
                />
              ) : null}
            </span>
          ) : (
            labels.notClassified
          ),
        },
        {
          label: labels.dataIssue,
          value:
            episode.dataIssueProbability === undefined ? en.kv.empty : formatPercentWhole(episode.dataIssueProbability),
        },
        {
          label: labels.outcome,
          value: episode.episodeEnd
            ? (alertsCopy.closeReason[episode.closeReason ?? ''] ??
              episode.closeReason ??
              alertsCopy.episodeStatus.CLOSED)
            : copy.disruptions.ongoing,
        },
        {
          label: labels.visibleTo,
          value: episode.audience ? (alertsCopy.audience[episode.audience] ?? episode.audience) : en.kv.empty,
        },
      ]}
    />
  );
}

/** A disruption of the Disruptions tab (§6, `disruption=<id>`): E-13 as the viewer sees it. */
export function DisruptionDrawer({
  id,
  route,
  timezone,
  onClose,
}: {
  id: string;
  route: RouteDetail;
  timezone: string;
  onClose: () => void;
}) {
  const query = useQuery(disruptionDetailQuery(id));
  const episode = query.data?.data;
  const direction = episode
    ? directionName(
        route.directions.find((d) => d.directionId === episode.directionId),
        episode.directionId,
      )
    : '';
  const open = episode?.status === 'OPEN';
  return (
    <DetailDrawer
      open
      onClose={onClose}
      title={
        <span className="flex flex-wrap items-center gap-2">
          {labels.title(route.displayName, direction)}
          {episode ? (
            open ? (
              <SeverityBadge severity={severityOf(episode)} size="sm" />
            ) : (
              <ToneBadge tone="neutral" size="sm" label={copy.drawer.ended} />
            )
          ) : null}
        </span>
      }
      footer={
        open ? (
          <Button asChild>
            <Link to="/map" search={{ route: [route.routeId], disruption: id }}>
              {labels.showOnMap}
            </Link>
          </Button>
        ) : undefined
      }
    >
      {query.isError && isNotFound(query.error) ? (
        <EmptyState title={labels.gone} />
      ) : query.isError && !episode ? (
        <ErrorState error={query.error} variant="block" onRetry={() => void query.refetch()} />
      ) : !episode ? (
        <PanelSkeleton variant="detail" />
      ) : (
        <Details episode={episode} route={route} timezone={timezone} />
      )}
    </DetailDrawer>
  );
}
