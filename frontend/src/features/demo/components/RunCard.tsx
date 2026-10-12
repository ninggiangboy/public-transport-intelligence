import { useQuery } from '@tanstack/react-query';
import { CircleCheck, LoaderCircle } from 'lucide-react';
import { useEffect, useState } from 'react';

import type { components } from '@/api/generated/schema';
import type { Scenario, ScenarioRun } from '@/api/sim';
import { AppLink } from '@/components/AppLink';
import { RouteBadge } from '@/components/RouteBadge';
import { Button, buttonVariants } from '@/components/ui/button';
import {
  followOf,
  formatClock,
  paramsSummary,
  requesterName,
  runProgress,
  targetOf,
  TARGET_PARAMS,
  watchedAlerts,
  watchesDeadLetters,
} from '@/features/demo/model';
import { deadLetterSummaryQuery, reactionAlertsQuery, suggestionQuery } from '@/features/demo/queries';
import { deadLetterBaseline } from '@/features/demo/use-demo-actions';
import { demoCopy } from '@/i18n/demo';
import { formatCount, formatDuration, formatPercentWhole } from '@/lib/format';
import { formatTime } from '@/lib/time';
import { useBusinessClock } from '@/lib/business-clock';
import { useReducedMotion } from '@/lib/use-reduced-motion';
import { cn } from '@/lib/utils';

type Route = components['schemas']['RouteItemResponse'];
type Alert = components['schemas']['AlertResponse'];

const copy = demoCopy.running;
/** "What happened" shows at most this many lines (§4.1). */
const MAX_LINES = 3;

interface RunCardProps {
  run: ScenarioRun;
  scenario: Scenario | undefined;
  route: Route | undefined;
  /** "Stop" was pressed: the card dims until the run is no longer `RUNNING` (§6). */
  stopping: boolean;
  onStop: () => void;
}

/** A running scenario (§4): who started it, its target and parameters, the time left and what the platform did. */
export function RunCard({ run, scenario, route, stopping, onStop }: RunCardProps) {
  const clock = useBusinessClock();
  const title = scenario?.title ?? run.scenario;
  const { routeId, salePointId } = targetOf(run.params);
  const follow = followOf(run.scenario, run.params);
  const target = routeId ? (route?.longName ?? route?.displayName ?? routeId) : salePointId;
  const headline = target ? `${title} · ${target}` : title;

  return (
    <article
      aria-label={title}
      aria-busy={stopping}
      className={cn(
        'grid overflow-hidden rounded-lg border border-border bg-card shadow-sm transition-opacity sm:grid-cols-[minmax(0,1fr)_220px]',
        stopping && 'opacity-60',
      )}
    >
      <div className="flex min-w-0 flex-col gap-2 px-5 py-4">
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span aria-hidden="true" className="size-2 rounded-full bg-tone-progress-solid" />
          {copy.started(
            formatTime(run.startedAt, { timeZone: clock.timezone, showZone: false }),
            requesterName(run.requestedBy),
          )}
        </p>
        <h3 className="flex items-center gap-2.5 text-panel font-semibold tracking-title">
          {routeId ? (
            <RouteBadge
              routeId={routeId}
              displayName={route?.displayName ?? routeId}
              {...(route?.color ? { color: route.color } : {})}
              {...(route?.textColor ? { textColor: route.textColor } : {})}
            />
          ) : null}
          <span className="min-w-0">{headline}</span>
        </h3>
        <p className="text-sm text-muted-foreground">{paramsSummary(scenario, run.params, TARGET_PARAMS)}</p>
        <WhatHappened run={run} />
      </div>
      <div className="flex flex-col items-center justify-center gap-3 border-t border-border px-5 py-4 sm:border-t-0 sm:border-l">
        <ProgressRing run={run} />
        <div className="flex flex-wrap justify-center gap-2">
          {follow ? (
            <AppLink href={follow.href} className={buttonVariants({ variant: 'outline', size: 'sm' })}>
              {demoCopy.feedback.follow[follow.kind]}
            </AppLink>
          ) : null}
          <Button variant="destructive" size="sm" disabled={stopping} onClick={onStop}>
            {copy.stop}
          </Button>
        </div>
      </div>
    </article>
  );
}

/** Re-renders every second, for the time left. */
function useSecondTick(): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, []);
  return now;
}

const RADIUS = 40;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/** The ring of `startedAt`–`plannedEndAt` with "{mm:ss} left" (§4); real time. */
function ProgressRing({ run }: { run: ScenarioRun }) {
  const now = useSecondTick();
  const progress = runProgress(run, now);
  if (!progress) return null;
  const percent = Math.round(progress.share * 100);
  return (
    <svg viewBox="0 0 96 96" width="96" height="96" role="img" aria-label={copy.progressLabel(percent)}>
      <circle cx="48" cy="48" r={RADIUS} fill="none" stroke="var(--muted-2)" strokeWidth="8" />
      <circle
        cx="48"
        cy="48"
        r={RADIUS}
        fill="none"
        stroke="var(--tone-progress-solid)"
        strokeWidth="8"
        strokeLinecap="round"
        strokeDasharray={`${CIRCUMFERENCE * progress.share} ${CIRCUMFERENCE}`}
        transform="rotate(-90 48 48)"
      />
      <text x="48" y="47" textAnchor="middle" className="fill-foreground text-[17px] font-semibold tabular-nums">
        {formatClock(progress.leftMs)}
      </text>
      <text x="48" y="63" textAnchor="middle" className="fill-muted-foreground text-[10.5px] font-medium">
        {copy.leftWord}
      </text>
    </svg>
  );
}

interface Line {
  key: string;
  text: string;
}

/**
 * "What happened" (§4.1): alerts on the run's route or sale point since it started, the dispatch suggestion of a
 * bunching alert, and the dead letters a bad-data run added. "Waiting for the platform to react…" until then.
 */
function WhatHappened({ run }: { run: ScenarioRun }) {
  const watched = watchedAlerts(run);
  const deadLetters = watchesDeadLetters(run.scenario);
  if (!watched && !deadLetters) return null;
  return (
    <section aria-label={copy.whatHappened} className="mt-1 flex flex-col gap-1">
      <h4 className="text-label font-medium text-foreground-2">{copy.whatHappened}</h4>
      <Reactions run={run} watched={watched} deadLetters={deadLetters} />
    </section>
  );
}

function belongsTo(alert: Alert, salePointId: string | undefined): boolean {
  if (!salePointId) return true;
  const named = alert.body.salePointId;
  // E-20 has no sale point filter; an alert whose body does not name one is kept.
  return typeof named !== 'string' || named === salePointId;
}

function Reactions({
  run,
  watched,
  deadLetters,
}: {
  run: ScenarioRun;
  watched: ReturnType<typeof watchedAlerts>;
  deadLetters: boolean;
}) {
  const reducedMotion = useReducedMotion();
  const alerts = useQuery({
    ...reactionAlertsQuery(run.startedAt, {
      ...(watched?.routeId ? { routeId: watched.routeId } : {}),
      ...(watched?.type ? { type: watched.type } : {}),
    }),
    enabled: watched !== undefined,
  });
  const started = Date.parse(run.startedAt);
  const matching = (alerts.data?.data.items ?? [])
    .filter((alert) => Date.parse(alert.createdAt) >= started && belongsTo(alert, watched?.salePointId))
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const bunching = matching.find((alert) => alert.type === 'BUNCHING' && alert.refId);
  const suggestion = useQuery({ ...suggestionQuery(bunching?.refId ?? ''), enabled: bunching?.refId !== undefined });
  const summary = useQuery({ ...deadLetterSummaryQuery(), enabled: deadLetters });

  const lines: Line[] = matching.map((alert) => ({
    key: alert.id,
    text: `${alert.title} · ${copy.afterStart(formatDuration(Math.max(0, Date.parse(alert.createdAt) - started)))}`,
  }));
  const first = suggestion.data?.data.items[0];
  if (first)
    lines.splice(1, 0, { key: 'suggestion', text: copy.suggestion(formatPercentWhole(first.actionConfidence)) });
  if (summary.data) {
    const added = summary.data.data.open - deadLetterBaseline(run.runId, summary.data.data.open);
    if (added > 0) lines.push({ key: 'dead-letters', text: copy.deadLetters(formatCount(added)) });
  }

  if (lines.length === 0) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        <LoaderCircle className={cn('size-4', !reducedMotion && 'animate-spin')} aria-hidden="true" />
        {copy.waiting}
      </p>
    );
  }
  return (
    <ul className="flex flex-col gap-1">
      {lines.slice(0, MAX_LINES).map((line) => (
        <li key={line.key} className="flex items-center gap-2 text-sm">
          <CircleCheck className="size-4 shrink-0 text-tone-success-solid" aria-hidden="true" />
          {line.text}
        </li>
      ))}
    </ul>
  );
}
