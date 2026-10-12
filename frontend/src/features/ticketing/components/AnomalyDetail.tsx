import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { isApiError } from '@/api/problem';
import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { CopyButton } from '@/components/CopyButton';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { SeverityBadge } from '@/components/SeverityBadge';
import { toneClasses } from '@/components/tone';
import { UnclassifiedChip } from '@/features/ticketing/components/AnomalyCells';
import type { RouteLook } from '@/features/ticketing/components/AnomalyTable';
import {
  anomalyLink,
  formatMoney,
  parseSummary,
  toSeverity,
  type Anomaly,
  type Summary,
} from '@/features/ticketing/model';
import { detailQuery } from '@/features/ticketing/queries';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';
import { ticketingCopy } from '@/i18n/ticketing';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCount, formatPercentWhole } from '@/lib/format';
import { formatTimeRange } from '@/lib/time';
import { cn } from '@/lib/utils';

const copy = ticketingCopy.detail;
const ONE_DECIMAL = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

interface AnomalyDetailProps {
  id: string;
  routes: ReadonlyMap<string, RouteLook>;
}

/** The anomaly of `?anomaly=` (§6.1): what triggered it, its recent windows and the AI's verdict. Read-only. */
export function AnomalyDetail({ id, routes }: AnomalyDetailProps) {
  const detail = useQuery(detailQuery(id));
  const anomaly = detail.data?.data;
  if (isApiError(detail.error) && detail.error.status === 404) {
    return <EmptyState title={copy.gone} />;
  }
  if (!anomaly) {
    return detail.isError ? (
      <div className="p-4">
        <ErrorState error={detail.error} variant="block" panel={copy.panel} onRetry={() => void detail.refetch()} />
      </div>
    ) : (
      <div className="p-4">
        <PanelSkeleton variant="detail" />
      </div>
    );
  }
  return <Body anomaly={anomaly} summary={parseSummary(anomaly.summary)} routes={routes} />;
}

function Body({
  anomaly,
  summary,
  routes,
}: {
  anomaly: Anomaly;
  summary: Summary;
  routes: ReadonlyMap<string, RouteLook>;
}) {
  const { timezone } = useBusinessClock();
  const salePoint = anomaly.salePointName ?? anomaly.salePointId;
  const title = copy.titles[anomaly.trigger]?.(salePoint) ?? salePoint;
  const window = formatTimeRange(anomaly.windowStart, anomaly.windowEnd, timezone);
  const route = anomaly.routeId ? routes.get(anomaly.routeId) : undefined;
  const kind = summary.salePointKind
    ? (copy.salePointKind[summary.salePointKind] ?? summary.salePointKind)
    : copy.salePointKindUnknown;
  const zScore = anomaly.zScore ?? summary.baseline?.zScore;

  return (
    <article aria-label={copy.label} className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1.5">
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <SeverityBadge severity={toSeverity(anomaly.severity)} size="sm" />
          <span className="tabular-nums">{window}</span>
        </p>
        <h2 className="text-panel font-semibold tracking-title">{title}</h2>
        <p className="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
          <span className="font-mono text-xs">{anomaly.salePointId}</span>
          <span aria-hidden="true">·</span>
          <span>{kind}</span>
          {anomaly.routeId ? (
            <>
              <span aria-hidden="true">·</span>
              <RouteBadge
                routeId={anomaly.routeId}
                displayName={route?.displayName ?? anomaly.routeId}
                color={route?.color}
                textColor={route?.textColor}
                size="sm"
              />
            </>
          ) : null}
        </p>
      </header>

      <dl className="grid grid-cols-4 divide-x divide-border rounded-lg border border-border">
        <Stat label={copy.transactions} value={formatCount(anomaly.txnCount)} />
        <Stat label={copy.refunds} value={formatCount(anomaly.refundCount)} />
        <Stat label={copy.refundRate} value={formatPercentWhole(anomaly.refundRatio)} />
        <Stat label={copy.zScore} value={zScore === undefined ? en.kv.empty : zScore.toFixed(2)} />
      </dl>

      {summary.previousWindows.length > 0 ? (
        <section className="flex flex-col gap-1.5">
          <h3 className="text-sm font-semibold">{copy.previous(summary.previousWindows.length)}</h3>
          <WindowBars counts={[...summary.previousWindows.map((window) => window.txnCount), anomaly.txnCount]} />
        </section>
      ) : null}
      <Baseline anomaly={anomaly} summary={summary} />

      <Callout tone="primary" title={copy.ai}>
        <AiClassification anomaly={anomaly} />
      </Callout>

      <Facts anomaly={anomaly} summary={summary} />

      <footer className="border-t border-border pt-3">
        <CopyButton value={anomalyLink(globalThis.location.origin, anomaly.id)} label={copy.copyLink}>
          {copy.copyLink}
        </CopyButton>
      </footer>
    </article>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 px-2 py-2">
      <dt className="text-xs leading-tight break-words text-muted-foreground">{label}</dt>
      <dd className="text-lg font-semibold tabular-nums">{value}</dd>
    </div>
  );
}

/** Columns of transactions per window, the current one last in `warning` (§6.1). */
function WindowBars({ counts }: { counts: number[] }) {
  const max = Math.max(1, ...counts);
  return (
    <div
      role="img"
      aria-label={copy.previousLabel(counts.map(formatCount).join(', '))}
      className="flex h-12 items-end gap-1"
    >
      {counts.map((count, index) => (
        <span
          // The windows are in a fixed order, oldest first: the position is their identity.
          key={`window-${String(index)}`}
          className={cn(
            'min-h-px flex-1 rounded-t-[2px]',
            index === counts.length - 1 ? toneClasses('warning').solid : 'bg-muted-2',
          )}
          style={{ height: `${(count / max) * 100}%` }}
        />
      ))}
    </div>
  );
}

function Baseline({ anomaly, summary }: { anomaly: Anomaly; summary: Summary }) {
  const baseline = summary.baseline;
  const mean = baseline?.mean ?? anomaly.baselineMean;
  const stddev = baseline?.stddev ?? anomaly.baselineStddev;
  if (!baseline || baseline.kind === 'NONE' || mean === undefined || stddev === undefined) {
    return <p className="text-sm text-muted-foreground">{copy.noBaseline}</p>;
  }
  return (
    <p className="text-sm text-muted-foreground">
      {copy.normal(
        ONE_DECIMAL.format(mean),
        ONE_DECIMAL.format(stddev),
        copy.baselineKind[baseline.kind] ?? baseline.kind,
        baseline.windows ?? 0,
      )}
    </p>
  );
}

function AiClassification({ anomaly }: { anomaly: Anomaly }) {
  if (!anomaly.category) {
    return <UnclassifiedChip label={copy.notClassified} />;
  }
  const model = anomaly.modelVersion ? { modelVersion: anomaly.modelVersion } : {};
  return (
    <dl className="flex flex-col gap-1.5">
      <Row label={copy.category}>
        <span className="font-medium">{alertsCopy.ticketingCategory[anomaly.category] ?? anomaly.category}</span>
        {anomaly.categoryConfidence !== undefined ? (
          <ConfidenceChip value={anomaly.categoryConfidence} {...model} />
        ) : null}
      </Row>
      {anomaly.severity !== undefined ? (
        <Row label={copy.severity}>
          <SeverityBadge severity={toSeverity(anomaly.severity)} size="sm" />
          {anomaly.severityConfidence !== undefined ? (
            <ConfidenceChip value={anomaly.severityConfidence} {...model} />
          ) : null}
        </Row>
      ) : null}
      {anomaly.modelVersion ? (
        <p className="font-mono text-xs text-muted-foreground">{copy.model(anomaly.modelVersion)}</p>
      ) : null}
    </dl>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <dt className="w-16 text-xs text-muted-foreground">{label}</dt>
      <dd className="flex flex-wrap items-center gap-2">{children}</dd>
    </div>
  );
}

function Facts({ anomaly, summary }: { anomaly: Anomaly; summary: Summary }) {
  const thresholds = summary.thresholds;
  const amount = summary.saleAmount
    ? copy.amount(
        formatMoney(anomaly.amountSum, summary.currency),
        formatMoney(summary.saleAmount.min, summary.currency),
        formatMoney(summary.saleAmount.max, summary.currency),
      )
    : copy.amountOnly(formatMoney(anomaly.amountSum, summary.currency));
  return (
    <section className="flex flex-col gap-2 text-sm">
      {summary.ticketTypes.length > 0 ? (
        <div className="flex flex-col gap-1.5">
          <h3 className="text-sm font-semibold">{copy.ticketTypes}</h3>
          <ul className="flex flex-wrap gap-1.5">
            {summary.ticketTypes.map(({ type, count }) => (
              <li key={type} className="rounded-full bg-muted px-2 py-0.5 text-xs tabular-nums">
                {copy.ticketType[type]?.(formatCount(count)) ?? `${type} ${formatCount(count)}`}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <p className="tabular-nums">{amount}</p>
      {summary.voidCount !== undefined && summary.voidCount > 0 ? (
        <p>{copy.voided(formatCount(summary.voidCount))}</p>
      ) : null}
      {thresholds ? (
        <p className="text-muted-foreground">
          {copy.thresholds(
            thresholds.volumeZ.toFixed(1),
            formatCount(thresholds.volumeMinTxn),
            formatPercentWhole(thresholds.refundRatio),
            formatCount(thresholds.refundMinCount),
          )}
        </p>
      ) : null}
    </section>
  );
}
