import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ExternalLink, Play } from 'lucide-react';
import { useCallback, useMemo } from 'react';

import { useAccess } from '@/app/access';
import { useEnv } from '@/app/env-context';
import { useFreshness } from '@/app/freshness';
import { AppLink } from '@/components/AppLink';
import { PageHeader } from '@/components/PageHeader';
import { toneClasses, type Tone } from '@/components/tone';
import { Button } from '@/components/ui/button';
import { ReadOnlyBadge } from '@/features/ops-jobs/components/parts';
import { PipelineStages } from '@/features/ops-jobs/components/PipelineStages';
import { RequestWatcher } from '@/features/ops-jobs/components/RequestWatcher';
import { RunDrawer } from '@/features/ops-jobs/components/RunDrawer';
import { RunsSection } from '@/features/ops-jobs/components/RunsSection';
import { SourcesCard } from '@/features/ops-jobs/components/SourcesCard';
import { ThroughputCard } from '@/features/ops-jobs/components/ThroughputCard';
import {
  consumerPaused,
  HREF,
  resolveBucket,
  resolvePeriod,
  streamStage,
  tabFilters,
  type Tab,
} from '@/features/ops-jobs/model';
import {
  daySummaryQuery,
  dlqSummaryQuery,
  flagsQuery,
  recentSummaryQuery,
  summaryQuery,
} from '@/features/ops-jobs/queries';
import { pipelineSearch, type PipelineSearch } from '@/features/ops-jobs/search';
import { useRunRequests } from '@/features/ops-jobs/use-run-requests';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { useDocumentTitle } from '@/lib/browser';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDateTime } from '@/lib/time';
import { cn } from '@/lib/utils';
import { useRealtime } from '@/realtime/useRealtime';

const copy = pipelineCopy.pipeline;
/** The dashboard behind the "Grafana" button (DOC-28 §7). */
const GRAFANA_DASHBOARD = '/d/pti-overview';

/** /ops/jobs: is the pipeline moving, and which runs failed (DOC-36 screens/ops-console-jobs). */
export function PipelinePage({ raw }: { raw: Record<string, unknown> }) {
  useDocumentTitle(copy.title);
  const navigate = useNavigate({ from: '/ops/jobs' });
  const access = useAccess();
  const appEnv = useEnv();
  const clock = useBusinessClock();
  const freshness = useFreshness();
  const operator = access.role === 'operator';
  useRealtime({ channels: ['jobs', 'dlq'] });

  // Bad values are dropped here, not in the route, so that the schema is not part of the first paint (DR-110).
  const search = useMemo(() => pipelineSearch.parse(raw), [raw]);
  const period = resolvePeriod(search);
  const bucket = resolveBucket(search.bucket, period.spanMs);
  const summary = useQuery(summaryQuery(period, bucket));
  const recent = useQuery(recentSummaryQuery());
  const day = useQuery(daySummaryQuery());
  const dlq = useQuery(dlqSummaryQuery());
  const flags = useQuery(flagsQuery());
  const requests = useRunRequests();

  const setSearch = useCallback(
    (change: Partial<PipelineSearch>, replace = true) => {
      void navigate({ search: (prev) => ({ ...pipelineSearch.parse(prev), ...change }), replace });
    },
    [navigate],
  );
  const openRun = useCallback(
    (runId: string) => {
      setSearch({ run: runId }, false);
    },
    [setSearch],
  );
  const setTab = (tab: Tab) => {
    setSearch({ ...tabFilters(tab), run: undefined }, false);
  };

  const stream = useMemo(() => (recent.data ? streamStage(recent.data.data) : undefined), [recent.data]);
  const sources = freshness.data?.sources.filter((source) => source.source !== 'GTFS_STATIC');
  const feeds = sources ? { total: sources.length, stale: sources.filter((s) => s.stale).length } : undefined;
  const paused = consumerPaused(flags.data?.data.items);
  const dlqData = dlq.data?.data;
  const batchJobs = day.data?.data.batchJobs;
  const tones: { sources: Tone; stream: Tone; deadLetters: Tone; batchJobs: Tone } = {
    sources: feeds && feeds.stale > 0 ? 'warning' : 'neutral',
    stream: stream && stream.failedRecently > 0 ? 'danger' : paused ? 'warning' : 'neutral',
    deadLetters: dlqData && dlqData.createdLastHour > 0 ? 'warning' : 'neutral',
    batchJobs: batchJobs && batchJobs.failed > 0 ? 'danger' : 'neutral',
  };
  const attention = [tones.sources, tones.stream, tones.deadLetters].filter((tone) => tone !== 'neutral').length;
  const failedToday = batchJobs?.failed ?? 0;

  const periodLabel =
    period.kind === 'window'
      ? copy.window[period.window]
      : `${formatDateTime(period.from, { timeZone: clock.timezone, showZone: false })} – ${formatDateTime(period.to, { timeZone: clock.timezone })}`;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[{ label: en.nav.groups.operations }, { label: copy.crumb }]}
        title={copy.title}
        subtitle={
          recent.data || day.data ? (
            <span className="inline-flex flex-wrap items-center gap-x-1.5">
              <span
                aria-hidden="true"
                className={cn('size-2 rounded-full', toneClasses(attention > 0 ? 'warning' : 'success').solid)}
              />
              <span>{attention > 0 ? copy.attention(attention) : copy.healthy}</span>
              {failedToday > 0 ? (
                <span className="text-tone-danger-fg">
                  {' · '}
                  {copy.failedToday(failedToday)}
                </span>
              ) : null}
            </span>
          ) : undefined
        }
        actions={
          <>
            {access.role === 'viewer' ? <ReadOnlyBadge /> : null}
            {appEnv.grafanaUrl ? (
              <Button asChild variant="outline">
                <a href={`${appEnv.grafanaUrl}${GRAFANA_DASHBOARD}`} target="_blank" rel="noreferrer noopener">
                  <ExternalLink aria-hidden="true" />
                  {copy.grafana}
                </a>
              </Button>
            ) : null}
            {operator ? (
              <Button asChild>
                <AppLink href={HREF.runJob}>
                  <Play aria-hidden="true" />
                  {copy.runJob}
                </AppLink>
              </Button>
            ) : null}
          </>
        }
      />

      <PipelineStages
        {...(stream ? { stream } : {})}
        {...(feeds ? { feeds } : {})}
        paused={paused}
        {...(dlqData ? { deadLetters: dlqData } : {})}
        {...(batchJobs ? { batchJobs } : {})}
        tones={tones}
        onSources={() => {
          const card = document.getElementById('pipeline-sources');
          card?.scrollIntoView({ block: 'center' });
          card?.focus({ preventScroll: true });
        }}
        onStream={() => {
          setTab('stream');
        }}
        onBatchJobs={() => {
          setTab('batch');
        }}
      />

      <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <ThroughputCard
          {...(summary.data ? { summary: summary.data.data } : {})}
          error={summary.error}
          onRetry={() => void summary.refetch()}
          periodLabel={periodLabel}
          zoomed={period.kind === 'fixed'}
          onZoom={(from, to) => {
            setSearch(
              {
                from: new Date(from).toISOString(),
                to: new Date(to).toISOString(),
                window: undefined,
                bucket: undefined,
              },
              false,
            );
          }}
          onResetZoom={() => {
            setSearch({ from: undefined, to: undefined, bucket: undefined }, false);
          }}
        />
        <SourcesCard
          {...(summary.data ? { summary: summary.data.data } : {})}
          error={summary.error}
          onRetry={() => void summary.refetch()}
          {...(freshness.data ? { freshness: freshness.data.sources } : {})}
        />
      </div>

      <RunsSection
        period={period}
        search={search}
        {...(summary.data ? { failedCount: summary.data.data.batchJobs.failed } : {})}
        onSearch={setSearch}
        onTab={setTab}
      />

      {search.run ? (
        <RunDrawer
          key={search.run}
          runId={search.run}
          operator={operator}
          requests={requests}
          onClose={() => {
            setSearch({ run: undefined }, false);
          }}
        />
      ) : null}
      {requests.tracked.map((request) => (
        <RequestWatcher key={request.id} request={request} onOpenRun={openRun} onDone={requests.done} />
      ))}
    </div>
  );
}
