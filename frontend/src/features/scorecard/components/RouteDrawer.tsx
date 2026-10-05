import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useState } from 'react';

import type { components } from '@/api/generated/schema';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { SegmentedControl } from '@/components/SegmentedControl';
import { SeverityBadge } from '@/components/SeverityBadge';
import { ToneBadge } from '@/components/ToneBadge';
import { Button } from '@/components/ui/button';
import { DelayAlongRoute } from '@/features/scorecard/components/DelayAlongRoute';
import { StatRow } from '@/features/scorecard/components/parts';
import { directionName } from '@/features/scorecard/display';
import { earlyPercent, latePercent, rangeInstants, type DayRange } from '@/features/scorecard/model';
import { recentDisruptionsQuery, routeDetailQuery, routeOtpQuery } from '@/features/scorecard/queries';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCount, formatDelaySeconds, formatPercent } from '@/lib/format';
import {
  formatDateTime,
  formatHourOfDay,
  formatTime,
  formatWeekdayLong,
  isSameDay,
  zonedWeekdayHour,
} from '@/lib/time';

type OtpItem = components['schemas']['Item'];
type RouteItem = components['schemas']['RouteItemResponse'];
type Disruption = components['schemas']['DisruptionResponse'];
type Direction = components['schemas']['DirectionResponse'];

const copy = scorecardCopy.scorecard;

interface RouteDrawerProps {
  routeId: string;
  range: DayRange;
  rangeLabel: string;
  /** The route's row of the ranking; fetched on its own when the row is not loaded (a link from the Overview). */
  item: OtpItem | undefined;
  route: RouteItem | undefined;
  timezone: string;
  /** `from` and `to` as they are on the URL, kept by "Open route details". */
  linkSearch: { from?: string; to?: string };
  onClose: () => void;
}

/** "Sat, Sep 27 · 3:58 PM – ongoing", "Thu, Sep 25 · 8:12 – 8:41 AM CDT". */
function episodeTime(episode: Disruption, timeZone: string): string {
  const start = formatDateTime(episode.episodeStart, { timeZone, showZone: false });
  if (!episode.episodeEnd) return `${start} – ${copy.drawer.ongoing}`;
  const end = isSameDay(episode.episodeStart, episode.episodeEnd, timeZone)
    ? formatTime(episode.episodeEnd, { timeZone })
    : formatDateTime(episode.episodeEnd, { timeZone });
  return `${start} – ${end}`;
}

function DisruptionItem({
  episode,
  directions,
  timeZone,
}: {
  episode: Disruption;
  directions: readonly Direction[];
  timeZone: string;
}) {
  const direction = directions.find((d) => d.directionId === episode.directionId);
  const cause = episode.likelyCause ? (alertsCopy.likelyCause[episode.likelyCause] ?? episode.likelyCause) : undefined;
  const detail = [
    directionName(direction, episode.directionId),
    copy.drawer.peak(formatDelaySeconds(episode.peakAvgDelaySeconds)),
    cause,
  ].filter(Boolean);
  const open = episode.status === 'OPEN';
  return (
    <li className="flex items-start justify-between gap-3 py-2.5">
      <span className="min-w-0">
        <span className="block text-sm font-medium tabular-nums">{episodeTime(episode, timeZone)}</span>
        <span className="mt-0.5 block text-xs text-muted-foreground">{detail.join(' · ')}</span>
      </span>
      {open ? (
        <SeverityBadge severity={episode.severity === 0 ? 0 : episode.severity === 1 ? 1 : 2} size="sm" />
      ) : (
        <ToneBadge tone="neutral" size="sm" label={copy.drawer.ended} />
      )}
    </li>
  );
}

/** The route summary of the ranking (§4, `route=<id>`): scores, typical delay along the route, recent disruptions. */
export function RouteDrawer({
  routeId,
  range,
  rangeLabel,
  item,
  route,
  timezone,
  linkSearch,
  onClose,
}: RouteDrawerProps) {
  const clock = useBusinessClock();
  const own = useQuery({ ...routeOtpQuery(range, routeId), enabled: item === undefined });
  const scores = item ?? own.data?.data.items[0];
  const detail = useQuery(routeDetailQuery(routeId));
  const directions = detail.data?.data.directions ?? [];
  const [chosen, setChosen] = useState<number | undefined>(undefined);
  const directionId = chosen ?? directions[0]?.directionId;
  const { dayOfWeek, hourOfDay } = zonedWeekdayHour(clock.now(), timezone);
  const instants = rangeInstants(range, timezone);
  const disruptions = useQuery(recentDisruptionsQuery(routeId, instants));
  const name = route?.displayName ?? detail.data?.data.displayName ?? routeId;
  const longName = route?.longName ?? detail.data?.data.longName;

  return (
    <DetailDrawer
      open
      onClose={onClose}
      title={
        <span className="flex items-center gap-3">
          <RouteBadge
            routeId={routeId}
            displayName={name}
            color={route?.color ?? detail.data?.data.color}
            textColor={route?.textColor ?? detail.data?.data.textColor}
            size="xl"
          />
          <span className="min-w-0 truncate">{longName ?? name}</span>
        </span>
      }
      footer={
        <>
          <Button variant="outline" asChild>
            <Link to="/map" search={{ route: [routeId] }}>
              {copy.drawer.seeLive}
            </Link>
          </Button>
          <Button asChild>
            <Link to="/scorecard/$routeId" params={{ routeId }} search={linkSearch}>
              {copy.drawer.openDetails}
            </Link>
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            {copy.drawer.meta(rangeLabel, scores ? formatCount(scores.tripCount) : en.kv.empty)}
          </p>
          {scores ? (
            <StatRow
              stats={[
                { label: copy.drawer.onTime, value: formatPercent(scores.otpPercentage / 100) },
                { label: copy.drawer.late, value: formatPercent(latePercent(scores) / 100) },
                { label: copy.drawer.early, value: formatPercent(earlyPercent(scores) / 100) },
              ]}
            />
          ) : own.isError ? (
            <ErrorState error={own.error} variant="inline" onRetry={() => void own.refetch()} />
          ) : own.isPending && item === undefined ? (
            <PanelSkeleton variant="detail" />
          ) : null}
        </div>

        <section className="flex flex-col gap-2.5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h3 className="text-sm font-semibold tracking-title">{copy.drawer.typicalDelay}</h3>
              <p className="text-xs text-muted-foreground">
                {copy.drawer.when(formatWeekdayLong(dayOfWeek), formatHourOfDay(hourOfDay))}
              </p>
            </div>
            {directions.length > 1 && directionId !== undefined ? (
              <SegmentedControl
                label={copy.direction.label}
                size="sm"
                value={String(directionId)}
                options={directions.map((d) => ({
                  value: String(d.directionId),
                  label: directionName(d, d.directionId),
                }))}
                onChange={(value) => {
                  setChosen(Number(value));
                }}
              />
            ) : null}
          </div>
          {detail.isError ? (
            <ErrorState error={detail.error} variant="inline" onRetry={() => void detail.refetch()} />
          ) : directionId === undefined ? (
            <PanelSkeleton variant="list" rows={4} />
          ) : (
            <DelayAlongRoute routeId={routeId} params={{ directionId, dayOfWeek, hourOfDay }} />
          )}
        </section>

        <section className="flex flex-col gap-1">
          <h3 className="text-sm font-semibold tracking-title">{copy.drawer.disruptions}</h3>
          {disruptions.isError ? (
            <ErrorState error={disruptions.error} variant="inline" onRetry={() => void disruptions.refetch()} />
          ) : !disruptions.data ? (
            <PanelSkeleton variant="list" rows={2} />
          ) : disruptions.data.data.items.length === 0 ? (
            <p className="py-2 text-sm text-muted-foreground">{copy.drawer.noDisruptions}</p>
          ) : (
            <ul className="divide-y divide-border">
              {disruptions.data.data.items.map((episode) => (
                <DisruptionItem key={episode.id} episode={episode} directions={directions} timeZone={timezone} />
              ))}
            </ul>
          )}
        </section>
      </div>
    </DetailDrawer>
  );
}
