import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import { useEffect, type ReactNode } from 'react';

import { api, write } from '@/api/client';
import type { components } from '@/api/generated/schema';
import { keys } from '@/api/keys';
import { useFreshness } from '@/app/freshness';
import { ActivityTimeline } from '@/components/ActivityTimeline';
import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { DispatchSuggestionCard } from '@/components/DispatchSuggestionCard';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { LineStrip } from '@/components/LineStrip';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SeverityBadge } from '@/components/SeverityBadge';
import type { ToneOrAccent } from '@/components/tone';
import { CompareBar, Section, StatRow } from '@/features/alerts/components/detail-parts';
import { alertmanagerPart, formatGap, type Alert } from '@/lib/alert-display';
import {
  bunchingDetailQuery,
  disruptionDetailQuery,
  routeDetailQuery,
  ticketingDetailQuery,
} from '@/features/alerts/queries';
import { alertsCopy } from '@/i18n/alerts';
import { useBusinessClock } from '@/lib/business-clock';
import {
  actorName,
  formatCount,
  formatDelaySeconds,
  formatDuration,
  formatPercentWhole,
  formatZScore,
} from '@/lib/format';
import { notify } from '@/lib/notify';
import { formatDateTime, formatTime, toMillis } from '@/lib/time';
import { useRelative, type TimeAxis } from '@/lib/use-now';

type PatternStop = components['schemas']['PatternStopResponse'];

const labels = alertsCopy.alerts.detailLabels;

/** Keys of the activity rows. */
const ROW = {
  detected: 'detected',
  ack: 'ack',
  resolved: 'resolved',
  classified: 'classified',
  suggestion: 'suggestion',
  feedback: 'feedback',
  ended: 'ended',
} as const;

interface ActivityItem {
  id: string;
  text: ReactNode;
  at: string;
  axis: TimeAxis;
  tone?: ToneOrAccent;
}

/** "Detected automatically", "Acknowledged by …", "Resolved" plus what the type adds, newest first (§6.1 item 6). */
function AlertActivity({ alert, extra = [] }: { alert: Alert; extra?: (ActivityItem | undefined)[] }) {
  const copy = alertsCopy.alerts.activity;
  const items: (ActivityItem | undefined)[] = [
    { id: ROW.detected, text: copy.detected, at: alert.createdAt, axis: 'audit', tone: 'neutral' },
    alert.acknowledgedAt
      ? {
          id: ROW.ack,
          text: copy.acknowledged(actorName(alert.acknowledgedBy ?? '')),
          at: alert.acknowledgedAt,
          axis: 'audit',
          tone: 'success',
        }
      : undefined,
    alert.resolvedAt
      ? { id: ROW.resolved, text: copy.resolved, at: alert.resolvedAt, axis: 'audit', tone: 'success' }
      : undefined,
    ...extra,
  ];
  const sorted = items
    .filter((item): item is ActivityItem => item !== undefined)
    .sort((a, b) => toMillis(b.at) - toMillis(a.at));
  return (
    <Section title={labels.activity}>
      <ActivityTimeline items={sorted} />
    </Section>
  );
}

/** The detail of an alert whose record is gone (404 for the caller, FR-09.5); the list drops the row too. */
function Gone({ onGone }: { onGone: () => void }) {
  useEffect(onGone, [onGone]);
  return <EmptyState title={alertsCopy.alerts.gone} />;
}

function isNotFound(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 404;
}

/** Up to two stops either side of the affected run of a direction (E-02), for the horizontal strip. */
function stopsAround(stops: readonly PatternStop[], ids: readonly string[], pad = 1): PatternStop[] {
  const indexes = stops.flatMap((stop, index) => (ids.includes(stop.stopId) ? [index] : []));
  if (indexes.length === 0) return [];
  const from = Math.max(0, Math.min(...indexes) - pad);
  const to = Math.min(stops.length - 1, Math.max(...indexes) + pad);
  return stops.slice(from, to + 1);
}

function useDirectionStops(routeId: string | undefined, directionId: number | undefined) {
  const route = useQuery({ ...routeDetailQuery(routeId ?? ''), enabled: routeId !== undefined });
  const direction = route.data?.data.directions.find((d) => d.directionId === directionId);
  return { stops: direction?.stops ?? [], color: route.data?.data.color };
}

interface BodyProps {
  alert: Alert;
  /** Viewer or operator: z-score, baselines and AI analysis. */
  staff: boolean;
  operator: boolean;
  onGone: () => void;
}

// ---------------------------------------------------------------------------------------------------------------------

export function DisruptionBody({ alert, staff, onGone }: BodyProps) {
  const query = useQuery({ ...disruptionDetailQuery(alert.refId ?? ''), enabled: alert.refId !== undefined });
  const episode = query.data?.data;
  const { stops, color } = useDirectionStops(episode?.routeId, episode?.directionId);
  if (query.isError && isNotFound(query.error)) return <Gone onGone={onGone} />;
  if (query.isError && !episode)
    return <ErrorState error={query.error} variant="block" onRetry={() => void query.refetch()} />;
  if (!episode) return alert.refId ? <PanelSkeleton variant="detail" /> : <AlertActivity alert={alert} />;

  const affected = episode.affectedStopIds;
  const strip = stopsAround(stops, affected);
  const pairs = strip.slice(0, -1).flatMap((stop, index) => {
    const next = strip[index + 1];
    return next && affected.includes(stop.stopId) && affected.includes(next.stopId)
      ? [{ from: stop.stopId, to: next.stopId, delayClass: 'very-late' as const }]
      : [];
  });
  const ai = episode.likelyCause;
  return (
    <>
      <StatRow
        stats={[
          { label: labels.averageDelay, value: formatDelaySeconds(episode.currentAvgDelaySeconds), tone: 'danger' },
          { label: labels.peakDelay, value: formatDelaySeconds(episode.peakAvgDelaySeconds) },
          { label: labels.stopsAffected, value: formatCount(affected.length) },
          ...(staff && episode.currentZScore !== undefined
            ? [{ label: labels.zScore, value: formatZScore(episode.currentZScore) }]
            : []),
        ]}
      />
      {strip.length > 0 ? (
        <Section title={labels.where} aside={alertsCopy.alerts.summary.stops(affected.length, stops.length)}>
          <LineStrip
            orientation="horizontal"
            color={color}
            stops={strip.map((stop) => ({ id: stop.stopId, name: stop.name }))}
            segments={pairs}
          />
        </Section>
      ) : null}
      {staff && episode.baselineMeanSeconds !== undefined ? (
        <Section title={labels.delayVsNormal}>
          <CompareBar
            label={labels.delayVsNormal}
            current={episode.currentAvgDelaySeconds}
            normal={episode.baselineMeanSeconds}
            currentText={formatDelaySeconds(episode.currentAvgDelaySeconds)}
            normalText={labels.normal(formatDelaySeconds(episode.baselineMeanSeconds))}
            tone="danger"
          />
        </Section>
      ) : null}
      {staff ? (
        <Callout tone="primary" title={labels.aiAnalysis}>
          {ai ? (
            <div className="flex flex-col gap-1.5">
              <p className="flex flex-wrap items-center gap-2 text-foreground">
                <span>
                  {labels.likelyCause}: <strong>{alertsCopy.likelyCause[ai] ?? ai}</strong>
                </span>
                {episode.causeConfidence !== undefined ? <ConfidenceChip value={episode.causeConfidence} /> : null}
              </p>
              {episode.dataIssueProbability !== undefined ? (
                <p>{labels.dataIssue(formatPercentWhole(episode.dataIssueProbability))}</p>
              ) : null}
              {episode.modelVersion ? <p className="font-mono text-xs">{episode.modelVersion}</p> : null}
            </div>
          ) : (
            <p>{labels.causeNotClassified}</p>
          )}
        </Callout>
      ) : null}
      <AlertActivity
        alert={alert}
        extra={[
          episode.enrichedAt && episode.causeConfidence !== undefined
            ? {
                id: ROW.classified,
                text: alertsCopy.alerts.activity.classified(formatPercentWhole(episode.causeConfidence)),
                at: episode.enrichedAt,
                axis: 'audit',
                tone: 'primary',
              }
            : undefined,
          episode.episodeEnd
            ? {
                id: ROW.ended,
                text: alertsCopy.alerts.activity.ended(
                  alertsCopy.closeReason[episode.closeReason ?? ''] ?? episode.closeReason ?? '',
                ),
                at: episode.episodeEnd,
                axis: 'event',
              }
            : undefined,
        ]}
      />
    </>
  );
}

// ---------------------------------------------------------------------------------------------------------------------

export function BunchingBody({ alert, operator, onGone }: BodyProps) {
  const queryClient = useQueryClient();
  const clock = useBusinessClock();
  const query = useQuery({ ...bunchingDetailQuery(alert.refId ?? ''), enabled: alert.refId !== undefined });
  const episode = query.data?.data;
  const { stops, color } = useDirectionStops(episode?.routeId, episode?.directionId);
  const feedback = useMutation({
    mutationFn: ({ id, value }: { id: string; value: 'accepted' | 'ignored' }) =>
      write(
        api.POST('/api/v1/insights/dispatch-suggestions/{id}/feedback', {
          params: { path: { id } },
          body: { feedback: value },
        }),
      ),
    onSuccess: () => {
      notify.success(alertsCopy.alerts.feedbackSaved);
      void queryClient.invalidateQueries({ queryKey: keys.insights.bunchingDetail(alert.refId ?? '') });
    },
    onError: () => {
      notify.error(alertsCopy.alerts.feedbackFailed);
    },
  });
  if (query.isError && isNotFound(query.error)) return <Gone onGone={onGone} />;
  if (query.isError && !episode)
    return <ErrorState error={query.error} variant="block" onRetry={() => void query.refetch()} />;
  if (!episode) return alert.refId ? <PanelSkeleton variant="detail" /> : <AlertActivity alert={alert} />;

  const end = episode.episodeEnd ? toMillis(episode.episodeEnd) : clock.now();
  const strip = episode.openStopId ? stopsAround(stops, [episode.openStopId], 2) : [];
  const suggestion = episode.suggestion;
  const buses = labels.buses(episode.vehicleLeader, episode.vehicleFollower);
  return (
    <>
      <StatRow
        stats={[
          { label: labels.headwayNow, value: formatGap(episode.lastGapSeconds), tone: 'warning' },
          { label: labels.scheduledHeadway, value: formatGap(episode.scheduledHeadwaySeconds) },
          { label: labels.smallestGap, value: formatGap(episode.minGapSeconds) },
          { label: labels.duration, value: formatDuration(Math.max(0, end - toMillis(episode.episodeStart))) },
        ]}
      />
      <Section title={labels.where} aside={buses}>
        {strip.length > 0 && episode.openStopId ? (
          <LineStrip
            orientation="horizontal"
            color={color}
            stops={strip.map((stop) => ({ id: stop.stopId, name: stop.name }))}
            marker={{ atStopId: episode.openStopId, label: buses }}
          />
        ) : null}
      </Section>
      <Section title={labels.gapVsHeadway}>
        <CompareBar
          label={labels.gapVsHeadway}
          current={episode.lastGapSeconds}
          normal={episode.scheduledHeadwaySeconds}
          currentText={formatGap(episode.lastGapSeconds)}
          normalText={labels.normal(formatGap(episode.scheduledHeadwaySeconds))}
          tone="warning"
        />
      </Section>
      {suggestion ? (
        <DispatchSuggestionCard
          suggestion={suggestion}
          busy={feedback.isPending}
          onFeedback={
            operator
              ? (value) => {
                  feedback.mutate({ id: suggestion.id, value });
                }
              : undefined
          }
        />
      ) : null}
      <AlertActivity
        alert={alert}
        extra={[
          suggestion
            ? {
                id: ROW.suggestion,
                text: alertsCopy.alerts.activity.suggestion(formatPercentWhole(suggestion.actionConfidence)),
                at: suggestion.createdAt,
                axis: 'audit',
                tone: 'primary',
              }
            : undefined,
          suggestion?.feedbackAt && suggestion.operatorFeedback
            ? {
                id: ROW.feedback,
                text: alertsCopy.alerts.activity.feedback(
                  alertsCopy.operatorFeedback[suggestion.operatorFeedback] ?? suggestion.operatorFeedback,
                  actorName(suggestion.feedbackBy ?? ''),
                ),
                at: suggestion.feedbackAt,
                axis: 'audit',
              }
            : undefined,
          episode.episodeEnd
            ? {
                id: ROW.ended,
                text: alertsCopy.alerts.activity.ended(
                  alertsCopy.closeReason[episode.closeReason ?? ''] ?? episode.closeReason ?? '',
                ),
                at: episode.episodeEnd,
                axis: 'event',
              }
            : undefined,
        ]}
      />
    </>
  );
}

// ---------------------------------------------------------------------------------------------------------------------

interface PreviousWindow {
  windowStart: string;
  txnCount: number;
}

function previousWindows(summary: Record<string, unknown> | undefined): PreviousWindow[] {
  const raw = summary?.previousWindows;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((entry: unknown) => {
    const record = entry as Partial<PreviousWindow> | null;
    return record && typeof record.windowStart === 'string' && typeof record.txnCount === 'number'
      ? [{ windowStart: record.windowStart, txnCount: record.txnCount }]
      : [];
  });
}

export function TicketingBody({ alert, onGone }: BodyProps) {
  const clock = useBusinessClock();
  const query = useQuery({ ...ticketingDetailQuery(alert.refId ?? ''), enabled: alert.refId !== undefined });
  const anomaly = query.data?.data;
  if (query.isError && isNotFound(query.error)) return <Gone onGone={onGone} />;
  if (query.isError && !anomaly)
    return <ErrorState error={query.error} variant="block" onRetry={() => void query.refetch()} />;
  if (!anomaly) return alert.refId ? <PanelSkeleton variant="detail" /> : <AlertActivity alert={alert} />;

  const windows = [
    ...previousWindows(anomaly.summary),
    { windowStart: anomaly.windowStart, txnCount: anomaly.txnCount },
  ];
  const peak = Math.max(1, ...windows.map((w) => w.txnCount));
  const classified = anomaly.category !== undefined && anomaly.category !== 'unclassified';
  return (
    <>
      <StatRow
        stats={[
          { label: labels.refundRate, value: formatPercentWhole(anomaly.refundRatio), tone: 'warning' },
          { label: labels.refunds, value: formatCount(anomaly.refundCount) },
          { label: labels.transactions, value: formatCount(anomaly.txnCount) },
          ...(anomaly.zScore !== undefined ? [{ label: labels.zScore, value: formatZScore(anomaly.zScore) }] : []),
        ]}
      />
      {windows.length > 1 ? (
        <Section
          title={labels.previousWindows}
          aside={
            anomaly.baselineMean !== undefined && anomaly.baselineStddev !== undefined
              ? labels.normalPerWindow(anomaly.baselineMean.toFixed(1), anomaly.baselineStddev.toFixed(1))
              : undefined
          }
        >
          <ol className="flex h-24 items-end gap-2">
            {windows.map((window, index) => {
              const current = index === windows.length - 1;
              return (
                <li
                  key={window.windowStart}
                  className="flex h-full flex-1 flex-col items-center justify-end gap-1"
                  aria-label={`${formatTime(window.windowStart, { timeZone: clock.timezone, showZone: false })}: ${formatCount(window.txnCount)}${current ? ` (${labels.current})` : ''}`}
                >
                  <span
                    className={
                      current
                        ? 'w-full rounded-t-sm bg-tone-warning-solid'
                        : 'w-full rounded-t-sm bg-tone-neutral-border'
                    }
                    style={{ height: `${Math.max(4, (window.txnCount / peak) * 100)}%` }}
                  />
                  <span className="text-[11px] text-muted-foreground tabular-nums" aria-hidden="true">
                    {formatTime(window.windowStart, { timeZone: clock.timezone, showZone: false })}
                  </span>
                </li>
              );
            })}
          </ol>
        </Section>
      ) : null}
      <Callout tone="primary" title={labels.classification}>
        {classified ? (
          <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 text-foreground">
            <dt className="text-muted-foreground">{labels.category}</dt>
            <dd className="flex flex-wrap items-center gap-2">
              {alertsCopy.ticketingCategory[anomaly.category ?? ''] ?? anomaly.category}
              {anomaly.categoryConfidence !== undefined ? <ConfidenceChip value={anomaly.categoryConfidence} /> : null}
            </dd>
            {anomaly.severity !== undefined ? (
              <>
                <dt className="text-muted-foreground">{labels.severityAi}</dt>
                <dd className="flex flex-wrap items-center gap-2">
                  <SeverityBadge severity={anomaly.severity as 0 | 1 | 2} size="sm" />
                  {anomaly.severityConfidence !== undefined ? (
                    <ConfidenceChip value={anomaly.severityConfidence} />
                  ) : null}
                </dd>
              </>
            ) : null}
          </dl>
        ) : (
          <p>{labels.notClassified}</p>
        )}
      </Callout>
      <AlertActivity alert={alert} />
    </>
  );
}

// ---------------------------------------------------------------------------------------------------------------------

function LastEvent({ at }: { at: string }) {
  return <p className="text-sm text-muted-foreground">{labels.lastEvent(useRelative(at, 'event'))}</p>;
}

/** DLQ_SEVERE, FEED_STALE, INFRA: what Alertmanager sent, no further request (screens/alert-feed §5). */
export function OpsBody({ alert }: BodyProps) {
  const clock = useBusinessClock();
  const { data: freshness } = useFreshness();
  const annotations = alertmanagerPart(alert, 'annotations');
  const alertLabels = alertmanagerPart(alert, 'labels');
  const startsAt = typeof alert.body.startsAt === 'string' ? alert.body.startsAt : undefined;
  const generator = typeof alert.body.generatorURL === 'string' ? alert.body.generatorURL : undefined;
  const source =
    alert.type === 'FEED_STALE' ? freshness?.sources.find((s) => s.source === alertLabels.source) : undefined;
  return (
    <>
      {annotations.summary || annotations.description ? (
        <div className="flex flex-col gap-1 text-sm">
          {annotations.summary ? <p className="font-medium">{annotations.summary}</p> : null}
          {annotations.description ? <p className="text-muted-foreground">{annotations.description}</p> : null}
        </div>
      ) : null}
      <Section
        title={labels.labels}
        aside={startsAt ? labels.started(formatDateTime(startsAt, { timeZone: clock.timezone })) : undefined}
      >
        <KeyValueList
          items={Object.entries(alertLabels).map(([label, value]) => ({
            label,
            value: <code className="font-mono">{value}</code>,
          }))}
        />
        {source?.lastEventAt ? <LastEvent at={source.lastEventAt} /> : null}
        {generator && /^https?:\/\//.test(generator) ? (
          <a
            href={generator}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex w-fit items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
          >
            {alertsCopy.alerts.actions.viewInGrafana}
            <ExternalLink className="size-3.5" aria-hidden="true" />
          </a>
        ) : null}
      </Section>
      <AlertActivity alert={alert} />
    </>
  );
}
