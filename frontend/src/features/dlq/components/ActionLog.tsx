import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { SearchX } from 'lucide-react';
import { useMemo } from 'react';

import type { WithAsOf } from '@/api/client';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { IdText } from '@/components/IdText';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { actorLabel, detailsSummary, sourceLabel, type DlqActionEntry } from '@/features/dlq/model';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { formatCount } from '@/lib/format';

const copy = dlqCopy.dlq;
const LOG_HEIGHT = 'calc(100svh - 21rem)';

type LogQuery = UseInfiniteQueryResult<{
  pages: WithAsOf<{ items: DlqActionEntry[]; nextCursor?: string }>[];
}>;

const col = columnHelper<DlqActionEntry>();
const columns = [
  col.accessor('at', {
    header: copy.columns.time,
    cell: (info) => <Timestamp at={info.getValue()} seconds showZone={false} />,
    meta: { className: 'whitespace-nowrap' },
  }),
  col.accessor('action', {
    header: copy.columns.action,
    cell: (info) => copy.action[info.getValue()] ?? info.getValue(),
    meta: { className: 'whitespace-nowrap' },
  }),
  col.accessor('actor', {
    header: copy.columns.actor,
    cell: (info) => <span title={info.getValue()}>{actorLabel(info.getValue())}</span>,
    meta: { className: 'whitespace-nowrap' },
  }),
  col.accessor('confidence', {
    header: copy.columns.confidence,
    cell: (info) => {
      const value = info.getValue();
      return value === undefined ? en.kv.empty : <ConfidenceChip value={value} />;
    },
    meta: { className: 'whitespace-nowrap' },
  }),
  col.accessor('deadLetterId', {
    header: copy.columns.deadLetter,
    cell: (info) => <IdText id={info.getValue()} copy={false} />,
  }),
  col.accessor('source', {
    header: copy.columns.source,
    cell: (info) => sourceLabel(info.getValue()),
    meta: { className: 'whitespace-nowrap' },
  }),
  col.accessor('status', {
    header: copy.columns.status,
    cell: (info) => <StatusPill domain="dlq" status={info.getValue()} size="sm" />,
    meta: { className: 'whitespace-nowrap' },
  }),
  col.display({
    id: 'details',
    header: copy.columns.details,
    cell: (info) => (
      <span className="block max-w-96 truncate text-muted-foreground" title={detailsSummary(info.row.original.details)}>
        {detailsSummary(info.row.original.details) || en.kv.empty}
      </span>
    ),
  }),
];

interface ActionLogProps {
  query: LogQuery;
  items: DlqActionEntry[];
  selectedId?: string;
  filtered: boolean;
  onOpen: (id: string) => void;
  onClearFilters: () => void;
}

/** The action log: what auto-triage and people did to dead letters, newest first (§4). */
export function ActionLog({ query, items, selectedId, filtered, onOpen, onClearFilters }: ActionLogProps) {
  const rowId = useMemo(() => (entry: DlqActionEntry) => String(entry.id), []);
  if (query.isError && items.length === 0) {
    return (
      <ErrorState
        error={query.error}
        variant="block"
        panel={copy.actionsCaption}
        onRetry={() => void query.refetch()}
      />
    );
  }
  return (
    <div>
      <DataTable
        columns={columns}
        data={items}
        getRowId={rowId}
        selectedId={
          selectedId === undefined ? undefined : items.find((entry) => entry.deadLetterId === selectedId)?.id.toString()
        }
        onRowOpen={(entry) => {
          onOpen(entry.deadLetterId);
        }}
        isLoading={query.isPending}
        dimmed={query.isPlaceholderData}
        caption={copy.actionsCaption}
        density="compact"
        virtual={{ height: LOG_HEIGHT, rowHeight: 44 }}
        hasNextPage={query.hasNextPage}
        fetchNextPage={() => void query.fetchNextPage()}
        isFetchingNextPage={query.isFetchingNextPage}
        empty={
          <EmptyState
            icon={filtered ? SearchX : undefined}
            title={filtered ? copy.empty.filteredTitle : copy.empty.actionsTitle}
            action={filtered ? { label: en.common.clearFilters, onClick: onClearFilters } : undefined}
          />
        }
      />
      {items.length > 0 ? (
        <p className="border-t border-border px-3 py-2 text-xs text-muted-foreground">
          {copy.loaded(formatCount(items.length))}
          {query.hasNextPage ? '+' : ''}
        </p>
      ) : null}
    </div>
  );
}
