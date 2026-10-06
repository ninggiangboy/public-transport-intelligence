import { CircleAlert, Inbox, PencilLine, SearchX } from 'lucide-react';
import { createContext, use, useCallback, useMemo } from 'react';

import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
  categoryLabel,
  errorCode,
  leftTab,
  MAX_SELECTED,
  select,
  shortId,
  showsStatus,
  sourceLabel,
  type DeadLetter,
} from '@/features/dlq/model';
import type { DlqTab } from '@/features/dlq/search';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { describeError } from '@/lib/problem-copy';
import { formatCount } from '@/lib/format';
import { notify } from '@/lib/notify';

const copy = dlqCopy.dlq;
/** The list is the height of the screen below the filters; rows are about this tall before they are measured. */
const LIST_HEIGHT = 'calc(100svh - 21rem)';
const ROW_HEIGHT = 96;

/** A row whose status left the tab fades until the next refetch removes it (§5). */
function rowClass(tab: DlqTab, item: DeadLetter) {
  return leftTab(tab, item.status) ? 'opacity-60' : undefined;
}

interface DlqListProps {
  tab: DlqTab;
  items: DeadLetter[];
  isPending: boolean;
  isError: boolean;
  error: unknown;
  isPlaceholderData: boolean;
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => void;
  refetch: () => void;
  selectedId?: string;
  onOpen: (id: string) => void;
  /** Operators get the checkboxes (§1). */
  operator: boolean;
  checked: readonly string[];
  onChecked: (ids: string[]) => void;
  /** Why a row of a bulk action failed, by id: its icon and tooltip (§6). */
  rowErrors: ReadonlyMap<string, unknown>;
  filtered: boolean;
  onClearFilters: () => void;
  onViewClosed: () => void;
}

interface ListState {
  tab: DlqTab;
  operator: boolean;
  checked: readonly string[];
  allChecked: boolean;
  rowErrors: ReadonlyMap<string, unknown>;
  toggle: (id: string, on: boolean) => void;
  toggleAll: (on: boolean) => void;
}

// What the cells need beyond their row. They read it from here, so that the column definitions keep their identity: a
// new `cell` function is a new component to React, and every row would remount whenever the data changed (AC-8).
const ListContext = createContext<ListState>({
  tab: 'review',
  operator: false,
  checked: [],
  allChecked: false,
  rowErrors: new Map(),
  toggle: () => undefined,
  toggleAll: () => undefined,
});

function SelectHeader() {
  const { operator, checked, allChecked, toggleAll } = use(ListContext);
  if (!operator) return copy.columns.record;
  return (
    <label className="flex items-center gap-2.5 font-medium">
      <Checkbox
        aria-label={copy.selectAll}
        checked={allChecked ? true : checked.length > 0 ? 'indeterminate' : false}
        onCheckedChange={(value) => {
          toggleAll(value === true);
        }}
      />
      {copy.columns.record}
    </label>
  );
}

function RecordCell({ item }: { item: DeadLetter }) {
  const { tab, operator, checked, rowErrors, toggle } = use(ListContext);
  const failure = rowErrors.get(item.id);
  const described = failure === undefined ? undefined : describeError(failure);
  return (
    <div className="flex items-start gap-2.5">
      {operator ? (
        <span
          className="pt-0.5"
          onClick={(event) => {
            event.stopPropagation();
          }}
          onKeyDown={(event) => {
            event.stopPropagation();
          }}
        >
          <Checkbox
            aria-label={copy.selectOne(shortId(item.id))}
            checked={checked.includes(item.id)}
            onCheckedChange={(value) => {
              toggle(item.id, value === true);
            }}
          />
        </span>
      ) : null}
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="flex min-w-0 items-center gap-1.5">
            {item.ruleId ? (
              <Tooltip>
                <TooltipTrigger asChild>
                  <span className="truncate font-mono text-sm font-medium">{errorCode(item)}</span>
                </TooltipTrigger>
                <TooltipContent>{copy.dq[item.ruleId] ?? item.ruleId}</TooltipContent>
              </Tooltip>
            ) : (
              <span className="truncate font-mono text-sm font-medium">{errorCode(item)}</span>
            )}
            {described ? (
              <Tooltip>
                <TooltipTrigger asChild>
                  <span tabIndex={0} className="text-tone-danger-fg">
                    <CircleAlert className="size-3.5" aria-label={copy.rowFailed} />
                  </span>
                </TooltipTrigger>
                <TooltipContent>{described.title}</TooltipContent>
              </Tooltip>
            ) : null}
          </span>
          <span className="shrink-0 text-xs text-muted-foreground">
            <Timestamp at={item.createdAt} format="time" showZone={false} seconds />
          </span>
        </div>
        <div className="truncate text-xs text-muted-foreground">
          {sourceLabel(item.source)} · {shortId(item.id)}
        </div>
        <div className="truncate text-sm" title={item.errorMessage}>
          {item.errorMessage}
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
          {item.category ? (
            <>
              <span>{categoryLabel(item.category)}</span>
              {item.categoryConfidence !== undefined ? <ConfidenceChip value={item.categoryConfidence} /> : null}
            </>
          ) : (
            <span>{copy.unclassified}</span>
          )}
          {item.hasEditedPayload ? <PencilLine className="size-3.5" aria-label={copy.edited} role="img" /> : null}
          {showsStatus(tab, item.status) ? <StatusPill domain="dlq" status={item.status} size="sm" /> : null}
        </div>
      </div>
    </div>
  );
}

const col = columnHelper<DeadLetter>();
const columns = [
  col.display({
    id: 'record',
    header: SelectHeader,
    cell: (info) => <RecordCell item={info.row.original} />,
    // `max-w-0 w-full` lets the cell take the table's width instead of the width of its longest message.
    meta: { className: 'w-full max-w-0' },
  }),
];

/** The dead letters of the tab, one composite cell per record, virtualised (DS-06, §4). */
export function DlqList(props: DlqListProps) {
  const { tab, items, operator, checked, onChecked, rowErrors } = props;
  const loadedIds = useMemo(() => items.map((item) => item.id), [items]);
  const allChecked = loadedIds.length > 0 && loadedIds.slice(0, MAX_SELECTED).every((id) => checked.includes(id));

  const toggle = useCallback(
    (id: string, on: boolean) => {
      if (!on) {
        onChecked(checked.filter((value) => value !== id));
        return;
      }
      const next = select(checked, [id]);
      if (next.capped) notify.message(copy.bulk.limit);
      onChecked(next.selected);
    },
    [checked, onChecked],
  );
  const toggleAll = useCallback(
    (on: boolean) => {
      if (!on) {
        onChecked([]);
        return;
      }
      const next = select([], loadedIds);
      if (next.capped || loadedIds.length > MAX_SELECTED) notify.message(copy.bulk.limit);
      onChecked(next.selected);
    },
    [loadedIds, onChecked],
  );
  const state = useMemo(
    () => ({ tab, operator, checked, allChecked, rowErrors, toggle, toggleAll }),
    [tab, operator, checked, allChecked, rowErrors, toggle, toggleAll],
  );

  const empty =
    tab === 'review' ? (
      props.filtered ? (
        <EmptyState
          icon={SearchX}
          title={copy.empty.filteredTitle}
          action={{ label: en.common.clearFilters, onClick: props.onClearFilters }}
        />
      ) : (
        <EmptyState
          icon={Inbox}
          title={copy.empty.reviewTitle}
          description={copy.empty.reviewBody}
          action={{ label: copy.empty.viewClosed, onClick: props.onViewClosed }}
        />
      )
    ) : props.filtered ? (
      <EmptyState
        icon={SearchX}
        title={copy.empty.filteredTitle}
        action={{ label: en.common.clearFilters, onClick: props.onClearFilters }}
      />
    ) : tab === 'confirm' ? (
      <EmptyState icon={Inbox} title={copy.empty.confirmTitle} description={copy.empty.confirmBody} />
    ) : (
      <EmptyState icon={Inbox} title={copy.empty.closedTitle} />
    );

  if (props.isError && items.length === 0) {
    return <ErrorState error={props.error} variant="block" panel={copy.list} onRetry={props.refetch} />;
  }

  return (
    <ListContext value={state}>
      {props.isError ? (
        <div className="px-3 pt-2">
          <ErrorState error={props.error} variant="inline" onRetry={props.refetch} />
        </div>
      ) : null}
      <DataTable
        columns={columns}
        data={items}
        getRowId={(item) => item.id}
        selectedId={props.selectedId}
        scrollToId={props.selectedId}
        onRowOpen={(item) => {
          props.onOpen(item.id);
        }}
        isLoading={props.isPending}
        dimmed={props.isPlaceholderData}
        caption={copy.list}
        density="compact"
        virtual={{ height: LIST_HEIGHT, rowHeight: ROW_HEIGHT }}
        hasNextPage={props.hasNextPage}
        fetchNextPage={props.fetchNextPage}
        isFetchingNextPage={props.isFetchingNextPage}
        rowClassName={(item) => rowClass(tab, item)}
        empty={empty}
      />
      {items.length > 0 ? (
        <p className="flex items-center justify-between gap-2 border-t border-border px-3 py-2 text-xs text-muted-foreground">
          <span>
            {copy.loaded(formatCount(items.length))}
            {props.hasNextPage ? '+' : ''}
          </span>
          {props.hasNextPage && !props.isFetchingNextPage ? (
            <Button variant="ghost" size="sm" onClick={props.fetchNextPage}>
              {en.common.loadMore}
            </Button>
          ) : null}
        </p>
      ) : null}
    </ListContext>
  );
}

/** The strip above the list that leads from "Review" to "Confirm" and back (§4). */
export function ConfirmHint({ tab, waiting, onReview }: { tab: DlqTab; waiting: number; onReview: () => void }) {
  if (tab === 'confirm') {
    return <Callout tone="primary">{copy.confirmBanner.suggested}</Callout>;
  }
  if (tab !== 'review' || waiting <= 0) return null;
  return (
    <Callout
      tone="primary"
      action={
        <Button variant="outline" size="sm" onClick={onReview}>
          {copy.confirmBanner.review}
        </Button>
      }
    >
      {copy.confirmBanner.waiting(waiting)}
    </Callout>
  );
}
