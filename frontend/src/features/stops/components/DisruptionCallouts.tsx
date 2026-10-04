import { useQuery } from '@tanstack/react-query';
import { CircleCheck } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';

import type { components } from '@/api/generated/schema';
import { AppLink } from '@/components/AppLink';
import { Callout } from '@/components/Callout';
import { disruptionQuery } from '@/features/stops/queries';
import { en } from '@/i18n/en';
import { formatDuration } from '@/lib/format';
import { formatTime } from '@/lib/time';

type StopDisruption = components['schemas']['StopDisruptionResponse'];
type StopRoute = components['schemas']['StopRouteResponse'];

const MAX_SHOWN = 3;
/** "Back to normal" stays this long after a disruption ends (UC-03 step 3). */
const BACK_TO_NORMAL_MS = 2 * 60_000;

function routeName(routes: readonly StopRoute[], routeId: string): string {
  return routes.find((route) => route.routeId === routeId)?.displayName ?? routeId;
}

/** "See alert" opens the episode on the live map (DOC-34 §5.3). */
function alertLink(disruption: StopDisruption): string {
  const search = new URLSearchParams({ route: disruption.routeId });
  if (disruption.disruptionId) search.set('disruption', disruption.disruptionId);
  return `/map?${search.toString()}`;
}

function DisruptionCallout({ disruption, timeZone }: { disruption: StopDisruption; timeZone: string }) {
  const id = disruption.disruptionId;
  // E-07 has no delay; the episode (E-13, public) has its current average.
  const episode = useQuery({ ...disruptionQuery(id ?? ''), enabled: id !== undefined });
  const delay = episode.data?.data.currentAvgDelaySeconds;
  const since = disruption.startedAt ? formatTime(disruption.startedAt, { timeZone, showZone: false }) : undefined;
  const body =
    since === undefined
      ? undefined
      : delay !== undefined && delay >= 60
        ? en.stops.disruption.body(formatDuration(Math.round(delay / 60) * 60_000), since)
        : en.stops.disruption.since(since);
  return (
    <Callout tone={disruption.severity >= 2 ? 'danger' : 'warning'} title={disruption.title}>
      {body ? <p>{body}</p> : null}
      <AppLink href={alertLink(disruption)} className="mt-1.5 inline-block underline underline-offset-2">
        {en.stops.disruption.seeAlert}
      </AppLink>
    </Callout>
  );
}

/**
 * A disruption that left E-07: "back to normal" for two minutes if its episode ended; nothing if the alert was
 * retracted or moved out of the caller's audience (FR-09.5), which E-13 answers with 404.
 */
function EndedCallout({
  disruption,
  routes,
  onDone,
}: {
  disruption: StopDisruption;
  routes: readonly StopRoute[];
  onDone: (alertId: string) => void;
}) {
  const { alertId } = disruption;
  const episode = useQuery({ ...disruptionQuery(disruption.disruptionId ?? ''), staleTime: 0 });
  // The cache still holds the episode as it was while open: decide on a fresh answer only.
  const settled = episode.isFetchedAfterMount && !episode.isFetching;
  const ended = settled && episode.data?.data.status !== undefined && episode.data.data.status !== 'OPEN';
  useEffect(() => {
    if (!settled) return;
    // Still open, retracted or hidden (404): nothing to say.
    if (episode.isError || !ended) {
      onDone(alertId);
      return;
    }
    const timer = setTimeout(() => {
      onDone(alertId);
    }, BACK_TO_NORMAL_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [settled, episode.isError, ended, onDone, alertId]);
  if (!ended) return null;
  return (
    <Callout tone="success" icon={<CircleCheck className="size-4" strokeWidth={1.75} />}>
      {en.stops.disruption.backToNormal(routeName(routes, disruption.routeId))}
    </Callout>
  );
}

interface DisruptionCalloutsProps {
  disruptions: readonly StopDisruption[];
  routes: readonly StopRoute[];
  timeZone: string;
}

/** Banners for the disruptions on the routes through the stop (UC-03), at most three, newest severity first. */
export function DisruptionCallouts({ disruptions, routes, timeZone }: DisruptionCalloutsProps) {
  const [previous, setPrevious] = useState(disruptions);
  const [ending, setEnding] = useState<StopDisruption[]>([]);
  const [announcement, setAnnouncement] = useState('');
  const done = useCallback((alertId: string) => {
    setEnding((current) => current.filter((d) => d.alertId !== alertId));
  }, []);

  // Compare with the last list during render (no effect): new disruptions are announced, removed ones checked.
  if (previous !== disruptions) {
    const now = new Set(disruptions.map((d) => d.alertId));
    const before = new Set(previous.map((d) => d.alertId));
    const removed = previous.filter((d) => !now.has(d.alertId) && d.disruptionId !== undefined);
    const added = disruptions.filter((d) => !before.has(d.alertId));
    setPrevious(disruptions);
    if (removed.length > 0) setEnding((current) => [...current, ...removed]);
    if (added.length > 0) setAnnouncement(added.map((d) => d.title).join('. '));
  }

  const shown = disruptions.slice(0, MAX_SHOWN);
  const more = disruptions.length - shown.length;
  return (
    <>
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {shown.length + ending.length > 0 ? (
        <div className="flex flex-col gap-2">
          {shown.map((disruption) => (
            <DisruptionCallout key={disruption.alertId} disruption={disruption} timeZone={timeZone} />
          ))}
          {more > 0 ? <p className="px-1 text-sm text-muted-foreground">{en.stops.disruption.more(more)}</p> : null}
          {ending.map((disruption) => (
            <EndedCallout key={disruption.alertId} disruption={disruption} routes={routes} onDone={done} />
          ))}
        </div>
      ) : null}
    </>
  );
}
