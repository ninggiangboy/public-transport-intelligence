import { ArrowLeft, Check, ExternalLink } from 'lucide-react';

import type { Access } from '@/app/access';
import { AppLink } from '@/components/AppLink';
import { CopyButton } from '@/components/CopyButton';
import { RouteBadge } from '@/components/RouteBadge';
import { SeverityBadge } from '@/components/SeverityBadge';
import { toneClasses } from '@/components/tone';
import { Button } from '@/components/ui/button';
import { BunchingBody, DisruptionBody, OpsBody, TicketingBody } from '@/features/alerts/components/AlertBodies';
import { alertVisual, linkAction, summaryLine, typeLabel, type Alert } from '@/features/alerts/model';
import { useAcknowledge } from '@/features/alerts/use-acknowledge';
import { en } from '@/i18n/en';
import { useBusinessClock } from '@/lib/business-clock';
import { actorName, actorOf } from '@/lib/format';
import { formatTime } from '@/lib/time';
import { useRelative } from '@/lib/use-now';
import { cn } from '@/lib/utils';

interface RouteLook {
  displayName: string;
  color?: string;
  textColor?: string;
}

interface AlertDetailProps {
  alert: Alert;
  access: Access;
  route?: RouteLook;
  /** The record behind the alert is gone for this caller: drop the row. */
  onGone: () => void;
  /** Narrow screens: back to the list. */
  onBack?: () => void;
}

/** When the thing started: the episode or window start of the body, else when the alert was raised. */
function startOf(alert: Alert): { at: string; axis: 'event' | 'audit' } {
  const fromBody = alert.body.episodeStart ?? alert.body.windowStart ?? alert.body.startsAt;
  return typeof fromBody === 'string' ? { at: fromBody, axis: 'event' } : { at: alert.createdAt, axis: 'audit' };
}

function Started({ alert }: { alert: Alert }) {
  const clock = useBusinessClock();
  const start = startOf(alert);
  const age = useRelative(start.at, start.axis);
  return (
    <>
      {en.alerts.summary.started(
        typeLabel(alert.type),
        formatTime(start.at, { timeZone: clock.timezone, showZone: false }),
        age,
      )}
    </>
  );
}

/** The right-hand panel of the feed (screens/alert-feed §6.1). */
export function AlertDetail({ alert, access, route, onGone, onBack }: AlertDetailProps) {
  const visual = alertVisual(alert);
  const link = linkAction(alert);
  const operator = access.role === 'operator';
  const staff = access.role !== undefined;
  const acknowledge = useAcknowledge(access.me?.username);
  const username = access.me?.username;
  const byYou = username !== undefined && alert.acknowledgedBy === actorOf(username);
  const ops = alert.type !== 'DISRUPTION' && alert.type !== 'BUNCHING' && alert.type !== 'TICKETING_ANOMALY';
  // Ops alerts show their Alertmanager summary in the body.
  const summary = ops ? undefined : summaryLine(alert);
  const permalink = `${globalThis.location.origin}/alerts?alert=${alert.id}`;
  const body = { alert, staff, operator, onGone };

  return (
    <article aria-labelledby={`alert-${alert.id}`} className="flex flex-col">
      <header className="flex flex-col gap-3 border-b border-border px-5 pt-5 pb-4 md:flex-row md:items-start md:justify-between md:px-7">
        <div className="flex min-w-0 gap-3.5">
          {onBack ? (
            <Button variant="ghost" size="icon-sm" aria-label={en.alerts.actions.back} onClick={onBack}>
              <ArrowLeft aria-hidden="true" />
            </Button>
          ) : null}
          <span
            className={cn('grid size-10 shrink-0 place-items-center rounded-[11px]', toneClasses(visual.tone).surface)}
            aria-hidden="true"
          >
            <visual.icon className="size-5" strokeWidth={1.75} />
          </span>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2 text-label text-muted-foreground">
              <SeverityBadge severity={alert.severity as 0 | 1 | 2} />
              <span title={staff ? (en.audience[alert.audience] ?? alert.audience) : undefined}>
                <Started alert={alert} />
              </span>
            </div>
            <h2 id={`alert-${alert.id}`} className="mt-1.5 text-[21px] leading-tight font-semibold tracking-title">
              {alert.title}
            </h2>
            {route || summary ? (
              <div className="mt-1.5 flex items-center gap-2 text-sm text-foreground-2">
                {route && alert.routeId ? (
                  <RouteBadge
                    routeId={alert.routeId}
                    displayName={route.displayName}
                    color={route.color}
                    textColor={route.textColor}
                  />
                ) : null}
                {summary ? <span className="truncate">{summary}</span> : null}
              </div>
            ) : null}
          </div>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          {link ? (
            link.external ? (
              <Button variant="outline" asChild>
                <a href={link.href} target="_blank" rel="noopener noreferrer">
                  {link.label}
                  <ExternalLink aria-hidden="true" />
                </a>
              </Button>
            ) : (
              <Button variant="outline" asChild>
                <AppLink href={link.href}>{link.label}</AppLink>
              </Button>
            )
          ) : null}
          {alert.acknowledgedAt ? (
            <span
              className={cn(
                'inline-flex h-8.5 items-center gap-1.5 rounded-md border px-3 text-sm font-medium',
                toneClasses('success').surface,
              )}
            >
              <Check className="size-4" aria-hidden="true" />
              {byYou ? en.alerts.acknowledgedByYou : en.alerts.acknowledgedBy(actorName(alert.acknowledgedBy ?? ''))}
            </span>
          ) : operator ? (
            <Button
              disabled={acknowledge.isPending}
              onClick={() => {
                acknowledge.mutate(alert.id);
              }}
            >
              {en.alerts.actions.acknowledge}
            </Button>
          ) : null}
          <CopyButton value={permalink} label={en.alerts.actions.copyLink} />
        </div>
      </header>
      <div className="flex flex-col gap-4 px-5 py-5 md:px-7">
        {alert.type === 'DISRUPTION' ? (
          <DisruptionBody {...body} />
        ) : alert.type === 'BUNCHING' ? (
          <BunchingBody {...body} />
        ) : alert.type === 'TICKETING_ANOMALY' ? (
          <TicketingBody {...body} />
        ) : (
          <OpsBody {...body} />
        )}
      </div>
    </article>
  );
}
