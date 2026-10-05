import { useQuery } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';

import type { components } from '@/api/generated/schema';
import { isApiError } from '@/api/problem';
import { AppLink } from '@/components/AppLink';
import { Card } from '@/components/Card';
import { CopyButton } from '@/components/CopyButton';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { KpiCard } from '@/components/KpiCard';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { GrafanaLinks } from '@/features/ops-jobs/components/parts';
import { deadLetterHref, formatOffsets, HREF, runHref } from '@/features/ops-jobs/model';
import { batchQuery } from '@/features/ops-jobs/queries';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { useDocumentTitle } from '@/lib/browser';
import { formatCount } from '@/lib/format';
import { useRealtime } from '@/realtime/useRealtime';

type Lineage = components['schemas']['BatchLineageResponse'];

const copy = pipelineCopy.lineage;

/** /ops/batches/$batchId: where a batch id came from, what it wrote, its dead letters and checks (§6.2, FR-12.5). */
export function BatchLineagePage({ batchId }: { batchId: string }) {
  const shortId = batchId.slice(0, 8);
  useDocumentTitle(copy.title(shortId));
  useRealtime({ channels: ['jobs'] });
  const lineage = useQuery(batchQuery(batchId));
  const data = lineage.data?.data;
  const notFound = isApiError(lineage.error) && lineage.error.status === 404;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[
          { label: en.nav.groups.operations },
          { label: pipelineCopy.pipeline.crumb, href: HREF.pipeline },
          { label: copy.crumb },
        ]}
        title={copy.title(shortId)}
        subtitle={
          data ? (
            <span className="inline-flex items-center gap-1">
              {data.origin === 'STREAM' ? copy.stream : copy.step}
              <span className="font-mono text-xs">{batchId}</span>
              <CopyButton value={batchId} label={copy.copyId} />
            </span>
          ) : undefined
        }
        actions={
          <>
            <GrafanaLinks {...(data?.links ? { links: data.links } : {})} />
            <Button
              variant="outline"
              size="sm"
              disabled={lineage.isFetching}
              aria-busy={lineage.isFetching}
              onClick={() => void lineage.refetch()}
            >
              <RefreshCw aria-hidden="true" />
              {pipelineCopy.pipeline.actions.refresh}
            </Button>
          </>
        }
      />
      {notFound ? (
        <Card>
          <EmptyState title={copy.notFound} description={copy.notFoundBody} />
        </Card>
      ) : lineage.isError && !data ? (
        <ErrorState
          error={lineage.error}
          variant="block"
          panel={pipelineCopy.pipeline.panels.batch}
          onRetry={() => void lineage.refetch()}
        />
      ) : !data ? (
        <PanelSkeleton variant="detail" />
      ) : (
        <LineageBody lineage={data} />
      )}
    </div>
  );
}

function LineageBody({ lineage }: { lineage: Lineage }) {
  const stream = lineage.origin === 'STREAM';
  const origin = [
    ...(lineage.runId
      ? [
          {
            label: copy.run,
            value: (
              <AppLink
                href={runHref(lineage.runId, lineage.startedAt)}
                className="font-mono text-primary hover:underline"
              >
                {lineage.runId}
              </AppLink>
            ),
          },
        ]
      : []),
    ...(stream
      ? [
          { label: copy.listener, value: <span className="font-mono">{lineage.listenerId ?? en.kv.empty}</span> },
          {
            label: copy.source,
            value: lineage.source
              ? ((en.source as Record<string, string>)[lineage.source] ?? lineage.source)
              : en.kv.empty,
          },
          { label: copy.instance, value: <span className="font-mono">{lineage.instanceId ?? en.kv.empty}</span> },
          {
            label: copy.offsets,
            value: <span className="font-mono">{formatOffsets(lineage.offsets) || en.kv.empty}</span>,
          },
          {
            label: copy.writeMode,
            value: lineage.writeMode
              ? ((pipelineCopy.writeMode as Record<string, string>)[lineage.writeMode] ?? lineage.writeMode)
              : en.kv.empty,
          },
        ]
      : [
          { label: copy.job, value: <span className="font-mono">{lineage.jobName ?? en.kv.empty}</span> },
          { label: copy.stepName, value: <span className="font-mono">{lineage.stepName ?? en.kv.empty}</span> },
        ]),
    { label: copy.started, value: lineage.startedAt ? <Timestamp at={lineage.startedAt} seconds /> : en.kv.empty },
    { label: copy.ended, value: lineage.endedAt ? <Timestamp at={lineage.endedAt} seconds /> : en.kv.empty },
    ...(lineage.replayRequestId
      ? [
          {
            label: copy.replay,
            value: (
              <AppLink href={HREF.replay(lineage.replayRequestId)} className="font-mono text-primary hover:underline">
                {lineage.replayRequestId.slice(0, 8)}
              </AppLink>
            ),
          },
        ]
      : []),
  ];
  const byStatus = Object.entries(lineage.deadLetters.byStatus).filter(([, count]) => count > 0);

  return (
    <>
      <Card title={copy.origin}>
        <KeyValueList items={origin} columns={2} />
      </Card>
      <div className="grid grid-cols-2 gap-3.5 xl:grid-cols-4">
        <KpiCard label={copy.read} value={formatCount(lineage.counts.read)} />
        <KpiCard label={copy.written} value={formatCount(lineage.counts.written)} />
        <KpiCard label={copy.skipped} value={formatCount(lineage.counts.skipped)} />
        <KpiCard
          label={copy.deadLetters}
          value={formatCount(lineage.deadLetters.total)}
          {...(lineage.deadLetters.total > 0 ? { tone: 'warning' as const } : {})}
          href={deadLetterHref(lineage)}
        />
      </div>
      <div className="grid gap-4 xl:grid-cols-2">
        <Card title={copy.byStatus}>
          {byStatus.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">{copy.noDeadLetters}</p>
          ) : (
            <table className="w-full text-sm">
              <caption className="sr-only">{copy.byStatusCaption}</caption>
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="py-1.5 font-medium">
                    {copy.statusColumn}
                  </th>
                  <th scope="col" className="py-1.5 text-right font-medium">
                    {copy.countColumn}
                  </th>
                </tr>
              </thead>
              <tbody>
                {byStatus.map(([status, count]) => (
                  <tr key={status} className="border-t border-border">
                    <td className="py-2">
                      <StatusPill domain="dlq" status={status} size="sm" />
                    </td>
                    <td className="py-2 text-right tabular-nums">{formatCount(count)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
        <Card title={copy.dataQuality}>
          {lineage.dataQuality.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">{copy.noChecks}</p>
          ) : (
            <table className="w-full text-sm">
              <caption className="sr-only">{copy.dqCaption}</caption>
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="py-1.5 font-medium">
                    {copy.dq.rule}
                  </th>
                  <th scope="col" className="py-1.5 font-medium">
                    {copy.dq.table}
                  </th>
                  <th scope="col" className="py-1.5 text-right font-medium">
                    {copy.dq.violations}
                  </th>
                  <th scope="col" className="py-1.5 text-right font-medium">
                    {copy.dq.checked}
                  </th>
                </tr>
              </thead>
              <tbody>
                {lineage.dataQuality.map((check) => (
                  <tr key={`${check.ruleId}-${check.tableName}`} className="border-t border-border">
                    <td className="py-2 font-mono text-xs">{check.ruleId}</td>
                    <td className="py-2 font-mono text-xs">{check.tableName}</td>
                    <td
                      className={
                        check.violationCount > 0
                          ? 'py-2 text-right font-semibold text-tone-warning-fg tabular-nums'
                          : 'py-2 text-right tabular-nums'
                      }
                    >
                      {formatCount(check.violationCount)}
                    </td>
                    <td className="py-2 text-right whitespace-nowrap">
                      <Timestamp at={check.checkedAt} format="time" seconds showZone={false} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>
    </>
  );
}
