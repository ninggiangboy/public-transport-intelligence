import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';

import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { StatusPill } from '@/components/StatusPill';
import { TimeRangePicker, type TimeRangeValue } from '@/components/TimeRangePicker';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { LiveDuration } from '@/features/ops-jobs/components/parts';
import {
  filterOptions,
  isBatchJob,
  MAX_SPAN_MS,
  mergeHead,
  streamMinute,
  TABS,
  tabOf,
  triggerLabel,
  type JobRun,
  type Period,
  type Tab,
} from '@/features/ops-jobs/model';
import { fetchRunsHead, HEAD_REFRESH_MS, runsQuery, type RunFilters } from '@/features/ops-jobs/queries';
import { WINDOWS, type PipelineSearch } from '@/features/ops-jobs/search';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCompact, formatCount } from '@/lib/format';
import { formatDateTime, formatTime } from '@/lib/time';
import { cn } from '@/lib/utils';

const copy = pipelineCopy.pipeline;
const TAB_LABEL: Record<Tab, string> = {
  all: copy.tabs.all,
  batch: copy.tabs.batch,
  stream: copy.tabs.stream,
  failed: copy.tabs.failed,
};
const statusLabels: Record<string, string> = en.status.job;

function useColumns(timeZone: string) {
  return useMemo(() => {
    const col = columnHelper<JobRun>();
    return [
      col.accessor('status', {
        header: copy.columns.status,
        cell: (info) => <StatusPill domain="job" status={info.getValue()} size="sm" />,
        meta: { className: 'w-36' },
      }),
      col.accessor('name', {
        header: copy.columns.job,
        cell: (info) => {
          const run = info.row.original;
          const minute = streamMinute(run.runId);
          return (
            <span className="block min-w-0">
              <span className="block truncate font-mono text-sm font-medium">{run.name}</span>
              <span className="block text-xs text-muted-foreground">
                {isBatchJob(run)
                  ? copy.runNumber(run.jobExecutionId ?? run.runId)
                  : minute
                    ? formatTime(minute, { timeZone, showZone: false })
                    : null}
              </span>
            </span>
          );
        },
      }),
      col.display({
        id: 'trigger',
        header: copy.columns.trigger,
        cell: (info) => <span className="whitespace-nowrap">{triggerLabel(info.row.original)}</span>,
      }),
      col.accessor('startedAt', {
        header: copy.columns.started,
        cell: (info) => {
          const at = info.getValue();
          return at ? <Timestamp at={at} showZone={false} seconds /> : en.kv.empty;
        },
        meta: { className: 'whitespace-nowrap' },
      }),
      col.display({
        id: 'duration',
        header: copy.columns.duration,
        cell: (info) => <LiveDuration run={info.row.original} />,
        meta: { className: 'whitespace-nowrap' },
      }),
      col.accessor('readCount', {
        header: copy.columns.read,
        cell: (info) => formatCompact(info.getValue()),
        meta: { align: 'right' },
      }),
      col.accessor('writeCount', {
        header: copy.columns.written,
        cell: (info) => formatCompact(info.getValue()),
        meta: { align: 'right' },
      }),
      col.accessor('skipCount', {
        header: copy.columns.skipped,
        cell: (info) => formatCompact(info.getValue()),
        meta: { align: 'right' },
      }),
      col.accessor('duplicateCount', {
        header: copy.columns.duplicates,
        cell: (info) => {
          const value = info.getValue();
          return value === undefined ? en.kv.empty : formatCompact(value);
        },
        meta: { align: 'right' },
      }),
    ];
  }, [timeZone]);
}

interface RunsSectionProps {
  period: Period;
  search: PipelineSearch;
  /** Failed batch jobs of the period, next to the "Failed" tab. */
  failedCount?: number;
  onSearch: (change: Partial<PipelineSearch>, replace?: boolean) => void;
  onTab: (tab: Tab) => void;
}

/** Tabs, filters and the runs of the period, newest first, 100 at a time (§4). */
export function RunsSection({ period, search, failedCount, onSearch, onTab }: RunsSectionProps) {
  const clock = useBusinessClock();
  const queryClient = useQueryClient();
  const columns = useColumns(clock.timezone);
  const tab = tabOf(search);
  const filters: RunFilters = useMemo(
    () => ({
      ...(search.kind ? { kind: search.kind } : {}),
      ...(search.status?.length ? { status: search.status } : {}),
      ...(search.name?.length ? { name: search.name } : {}),
    }),
    [search.kind, search.status, search.name],
  );
  const options = runsQuery(period, filters);
  const runs = useInfiniteQuery(options);
  const items = useMemo(() => runs.data?.pages.flatMap((page) => page.data.items) ?? [], [runs.data]);

  // Micro-batches emit no job.run (DOC-33 §5.7): the first page again every 60 s, over the loaded ones (§5).
  const queryKey = options.queryKey;
  useEffect(() => {
    if (search.kind === 'BATCH_JOB' || period.kind === 'fixed') return;
    const timer = setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      fetchRunsHead(period, filters).then(
        ({ data }) => {
          queryClient.setQueryData(queryKey, (cached) => mergeHead(cached, data.items));
        },
        () => undefined,
      );
    }, HEAD_REFRESH_MS);
    return () => {
      clearInterval(timer);
    };
  }, [queryClient, queryKey, period, filters, search.kind]);

  const { names, statuses } = filterOptions(search.kind);
  const range: TimeRangeValue =
    period.kind === 'window' ? { window: period.window } : { from: period.from, to: period.to };
  const caption = copy.tableCaption(
    period.kind === 'fixed'
      ? formatDateTime(period.from, { timeZone: clock.timezone, showZone: false })
      : copy.window[period.window],
    period.kind === 'fixed' ? formatDateTime(period.to, { timeZone: clock.timezone }) : en.time.now,
  );

  return (
    <section
      aria-label={copy.panels.runs}
      className="overflow-hidden rounded-lg border border-border bg-card shadow-sm"
    >
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-border px-4 pt-2">
        <div role="tablist" aria-label={copy.tabs.label} className="flex">
          {TABS.map((value) => {
            const active = tab === value;
            return (
              <button
                key={value}
                type="button"
                role="tab"
                id={`pipeline-tab-${value}`}
                aria-selected={active}
                aria-controls="pipeline-runs"
                tabIndex={active ? 0 : -1}
                onClick={() => {
                  if (!active) onTab(value);
                }}
                onKeyDown={(event) => {
                  const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
                  if (step === 0) return;
                  event.preventDefault();
                  const next = TABS[(TABS.indexOf(value) + step + TABS.length) % TABS.length] ?? value;
                  onTab(next);
                  document.getElementById(`pipeline-tab-${next}`)?.focus();
                }}
                className={cn(
                  '-mb-px inline-flex h-10 items-center gap-1.5 border-b-2 px-3 text-sm font-medium',
                  active
                    ? 'border-foreground text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground',
                )}
              >
                {TAB_LABEL[value]}
                {value === 'failed' && failedCount !== undefined && failedCount > 0 ? (
                  <span className="rounded-full bg-tone-danger-bg px-1.5 text-xs text-tone-danger-fg tabular-nums">
                    {formatCount(failedCount)}
                  </span>
                ) : null}
              </button>
            );
          })}
        </div>
        <div className="flex flex-wrap items-center gap-2 pb-2">
          <MultiSelectFilter
            label={copy.filters.status}
            options={statuses.map((value) => ({ value, label: statusLabels[value] ?? value }))}
            value={search.status ?? []}
            onChange={(value) => {
              onSearch({ status: value.length > 0 ? value : undefined });
            }}
          />
          <MultiSelectFilter
            label={copy.filters.job}
            options={names.map((value) => ({ value, label: value }))}
            value={search.name ?? []}
            onChange={(value) => {
              onSearch({ name: value.length > 0 ? value : undefined });
            }}
          />
          <TimeRangePicker
            value={range}
            presets={[...WINDOWS]}
            maxRangeSeconds={MAX_SPAN_MS / 1000}
            granularity="minute"
            onChange={(value) => {
              onSearch({
                window: value.window as PipelineSearch['window'],
                from: value.from,
                to: value.to,
                bucket: undefined,
              });
            }}
          />
        </div>
      </div>
      <div id="pipeline-runs" role="tabpanel" aria-labelledby={`pipeline-tab-${tab}`}>
        {runs.isError && !runs.data ? (
          <ErrorState error={runs.error} variant="block" panel={copy.panels.runs} onRetry={() => void runs.refetch()} />
        ) : (
          <DataTable
            columns={columns}
            data={items}
            getRowId={(run) => run.runId}
            selectedId={search.run}
            onRowOpen={(run) => {
              onSearch({ run: run.runId }, false);
            }}
            isLoading={!runs.data}
            dimmed={runs.isPlaceholderData}
            caption={caption}
            density="compact"
            empty={<EmptyState title={copy.empty.title} description={copy.empty.body} />}
          />
        )}
        {runs.hasNextPage ? (
          <div className="flex justify-center border-t border-border px-4 py-3">
            <Button
              variant="outline"
              size="sm"
              disabled={runs.isFetchingNextPage}
              aria-busy={runs.isFetchingNextPage}
              onClick={() => void runs.fetchNextPage()}
            >
              {copy.loadOlder}
            </Button>
          </div>
        ) : null}
      </div>
    </section>
  );
}
