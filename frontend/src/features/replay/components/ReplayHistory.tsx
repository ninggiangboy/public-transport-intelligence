import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { useAccess } from '@/app/access';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { IdText } from '@/components/IdText';
import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SegmentedControl } from '@/components/SegmentedControl';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { isActive, progressPercent, type Replay } from '@/features/replay/model';
import { estimateQuery, historyQuery, replayQuery } from '@/features/replay/queries';
import { KINDS, STATUSES, type ReplayKind, type ReplaySearch, type ReplayStatus } from '@/features/replay/search';
import { en } from '@/i18n/en';
import { replayCopy } from '@/i18n/replay';
import { useBusinessClock } from '@/lib/business-clock';
import { actorName, formatCompact, formatCount, formatDuration } from '@/lib/format';
import { formatDateTime } from '@/lib/time';

const copy = replayCopy.history;
const sourceLabels: Partial<Record<string, string>> = en.source;
const statusLabels: Partial<Record<string, string>> = en.status.replay;
/** A failure message is cut in the table; the drawer has it whole. */
const MESSAGE_CHARS = 60;

interface ReplayHistoryProps {
  kind: ReplayKind | undefined;
  status: ReplayStatus[] | undefined;
  selectedId: string | undefined;
  onSearch: (change: Partial<ReplaySearch>) => void;
  onOpen: (id: string) => void;
}

/** Tab "History" (§4): every replay of the last 7 days, newest first, filtered by kind and status on the URL. */
export function ReplayHistory({ kind, status, selectedId, onSearch, onOpen }: ReplayHistoryProps) {
  const filters = useMemo(() => ({ ...(kind ? { kind } : {}), ...(status?.length ? { status } : {}) }), [kind, status]);
  const history = useInfiniteQuery(historyQuery(filters));
  const items = useMemo(() => history.data?.pages.flatMap((page) => page.data.items) ?? [], [history.data]);
  const filtered = kind !== undefined || (status?.length ?? 0) > 0;

  return (
    <section aria-label={copy.label} className="rounded-lg border border-border bg-card shadow-sm">
      <div className="flex flex-wrap items-center gap-2 px-4 py-3">
        <SegmentedControl
          label={copy.kind}
          size="sm"
          value={kind ?? 'all'}
          options={[
            { value: 'all', label: en.filters.all },
            ...KINDS.map((value) => ({ value, label: replayCopy.kind[value] ?? value })),
          ]}
          onChange={(value) => {
            onSearch({ kind: value === 'all' ? undefined : value });
          }}
        />
        <MultiSelectFilter
          label={copy.status}
          options={STATUSES.map((value) => ({ value, label: statusLabels[value] ?? value }))}
          value={status ?? []}
          onChange={(value) => {
            onSearch({ status: value.length > 0 ? value : undefined });
          }}
        />
      </div>
      <div className="border-t border-border">
        {history.isPending ? (
          <PanelSkeleton variant="table" rows={6} />
        ) : history.isError && items.length === 0 ? (
          <ErrorState error={history.error} variant="block" panel={copy.label} onRetry={() => void history.refetch()} />
        ) : (
          <>
            {history.isError ? (
              <ErrorState
                error={history.error}
                variant="inline"
                panel={copy.label}
                onRetry={() => void history.refetch()}
              />
            ) : null}
            <DataTable
              columns={columns}
              data={items}
              getRowId={(replay) => replay.id}
              selectedId={selectedId}
              onRowOpen={(replay) => {
                onOpen(replay.id);
              }}
              dimmed={history.isPlaceholderData}
              caption={copy.caption(items.length)}
              density="compact"
              empty={
                filtered ? (
                  <EmptyState
                    title={en.states.empty.noResults.title}
                    description={en.states.empty.noResults.description}
                    action={{
                      label: en.common.clearFilters,
                      onClick: () => {
                        onSearch({ kind: undefined, status: undefined });
                      },
                    }}
                  />
                ) : (
                  <EmptyState title={copy.empty} description={copy.emptyBody} />
                )
              }
            />
          </>
        )}
        {history.hasNextPage ? (
          <div className="flex justify-center border-t border-border px-4 py-3">
            <Button
              variant="outline"
              size="sm"
              disabled={history.isFetchingNextPage}
              aria-busy={history.isFetchingNextPage}
              onClick={() => void history.fetchNextPage()}
            >
              {copy.loadOlder}
            </Button>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function RangeCell({ replay }: { replay: Replay }) {
  const clock = useBusinessClock();
  if (replay.kind === 'DLQ_RECORD') return replay.deadLetterId ? <IdText id={replay.deadLetterId} /> : en.kv.empty;
  if (!replay.fromTs || !replay.toTs) return en.kv.empty;
  return (
    <span className="whitespace-nowrap">
      {formatDateTime(replay.fromTs, { timeZone: clock.timezone, showZone: false })}
      {' → '}
      {formatDateTime(replay.toTs, { timeZone: clock.timezone, showZone: false })}
    </span>
  );
}

/**
 * A running replay follows E-52 (E-51 has no progress) and, for an operator, its E-53 estimate; a finished one says how
 * long it took, since E-51 has no statistics; a failed one shows its message (§4).
 */
function ProgressCell({ replay }: { replay: Replay }) {
  if (isActive(replay.status)) return <RunningProgress replay={replay} />;
  if (replay.status === 'FAILED') {
    const message = replay.message ?? '';
    return (
      <span title={message} className="text-xs text-tone-danger-fg">
        {message.length > MESSAGE_CHARS ? `${message.slice(0, MESSAGE_CHARS)}…` : message || en.kv.empty}
      </span>
    );
  }
  if (replay.startedAt && replay.finishedAt) {
    return (
      <span className="text-xs text-muted-foreground">
        {copy.took(formatDuration(Date.parse(replay.finishedAt) - Date.parse(replay.startedAt)))}
      </span>
    );
  }
  return en.kv.empty;
}

function RunningProgress({ replay }: { replay: Replay }) {
  const access = useAccess();
  const detail = useQuery(replayQuery(replay.id, 'row'));
  const progress = detail.data?.data.progress;
  const range = replay.kind === 'RAW_RANGE' && replay.fromTs && replay.toTs;
  const estimate = useQuery({
    ...estimateQuery({ source: replay.source, fromTs: replay.fromTs ?? '', toTs: replay.toTs ?? '' }),
    staleTime: Number.POSITIVE_INFINITY,
    enabled: Boolean(range) && access.role === 'operator',
  });
  const total = estimate.data?.data.estimatedMessages;
  if (!progress) return <Progress aria-label={replayCopy.drawer.progressLabel} className="w-28" value={null} />;
  const percent = progressPercent(progress.readCount, total);
  return (
    <span className="flex min-w-36 flex-col gap-1">
      <Progress aria-label={replayCopy.drawer.progressLabel} value={percent ?? null} className="w-28" />
      <span className="text-xs text-muted-foreground tabular-nums">
        {total !== undefined
          ? copy.progress(formatCompact(progress.readCount), formatCompact(total))
          : copy.read(formatCount(progress.readCount))}
      </span>
    </span>
  );
}

const columns = (() => {
  const col = columnHelper<Replay>();
  const c = copy.columns;
  return [
    col.accessor('status', {
      header: c.status,
      cell: (info) => <StatusPill domain="replay" status={info.getValue()} size="sm" />,
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('kind', {
      header: c.kind,
      cell: (info) => replayCopy.kind[info.getValue()] ?? info.getValue(),
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('source', {
      header: c.source,
      cell: (info) => sourceLabels[info.getValue()] ?? info.getValue(),
      meta: { className: 'whitespace-nowrap' },
    }),
    col.display({ id: 'range', header: c.range, cell: (info) => <RangeCell replay={info.row.original} /> }),
    col.display({ id: 'progress', header: c.progress, cell: (info) => <ProgressCell replay={info.row.original} /> }),
    col.accessor('recomputeAnalytics', {
      header: c.recompute,
      cell: (info) => (info.getValue() ? replayCopy.yes : replayCopy.no),
    }),
    col.display({
      id: 'requested',
      header: c.requested,
      cell: (info) => (
        <span className="whitespace-nowrap">
          {actorName(info.row.original.requestedBy)}
          {' · '}
          <Timestamp at={info.row.original.requestedAt} format="time" showZone={false} />
        </span>
      ),
    }),
  ];
})();
