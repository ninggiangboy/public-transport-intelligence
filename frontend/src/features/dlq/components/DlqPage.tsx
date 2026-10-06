import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Lock } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { useAccess } from '@/app/access';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { DetailDrawer } from '@/components/DetailDrawer';
import { EmptyState } from '@/components/EmptyState';
import { SplitView } from '@/components/SplitView';
import { ToneBadge } from '@/components/ToneBadge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { ActionLog } from '@/features/dlq/components/ActionLog';
import { BulkBar } from '@/features/dlq/components/BulkBar';
import { DiscardFields } from '@/features/dlq/components/DiscardFields';
import { DlqDetail } from '@/features/dlq/components/DlqDetail';
import { watchDeadLetterHeads } from '@/features/dlq/heads';
import { DlqFilters } from '@/features/dlq/components/DlqFilters';
import { ConfirmHint, DlqList } from '@/features/dlq/components/DlqList';
import {
  AWAITING,
  clearedFilters,
  discardReasonText,
  discardValid,
  isFiltered,
  listFilters,
  type BulkKind,
  type DiscardReason,
} from '@/features/dlq/model';
import { actionsQuery, dlqSummaryQuery, listQuery } from '@/features/dlq/queries';
import { dlqSearch, TABS, type DlqSearch, type DlqTab } from '@/features/dlq/search';
import { useDlqActions } from '@/features/dlq/use-dlq-actions';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';
import { isTyping } from '@/lib/keyboard';
import { formatCount } from '@/lib/format';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { useRealtime } from '@/realtime/useRealtime';

const copy = dlqCopy.dlq;

function ReadOnly() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex rounded-sm">
          <ToneBadge tone="neutral" icon={Lock} label={copy.readOnly} />
        </span>
      </TooltipTrigger>
      <TooltipContent>{copy.readOnlyHelp}</TooltipContent>
    </Tooltip>
  );
}

/** /ops/dlq: the records that failed validation, to review, replay or discard (DOC-36 screens/ops-console-dlq). */
export function DlqPage({ raw }: { raw: Record<string, unknown> }) {
  useDocumentTitle(copy.title);
  const navigate = useNavigate({ from: '/ops/dlq' });
  const access = useAccess();
  const operator = access.role === 'operator';
  const actions = useDlqActions();
  useRealtime({ channels: ['dlq'] });
  const queryClient = useQueryClient();
  useEffect(() => watchDeadLetterHeads(queryClient), [queryClient]);

  // Bad values are dropped here, not in the route, so that the schema is not part of the first paint (DR-110).
  const search = useMemo(() => dlqSearch.parse(raw), [raw]);
  const tab: DlqTab = search.tab ?? 'review';
  const summary = useQuery(dlqSummaryQuery());
  const filters = useMemo(() => listFilters(tab, search), [tab, search]);
  const list = useInfiniteQuery({ ...listQuery(filters), enabled: tab !== 'actions' });
  const log = useInfiniteQuery({ ...actionsQuery(search), enabled: tab === 'actions' });
  const items = useMemo(() => list.data?.pages.flatMap((page) => page.data.items) ?? [], [list.data]);
  const entries = useMemo(() => log.data?.pages.flatMap((page) => page.data.items) ?? [], [log.data]);
  const filtered = isFiltered(tab, search);
  const awaiting = summary.data?.data.byStatus[AWAITING] ?? 0;

  const setSearch = useCallback(
    (change: Partial<DlqSearch>, replace = true) => {
      void navigate({ search: (previous) => ({ ...dlqSearch.parse(previous), ...change }), replace });
    },
    [navigate],
  );

  // An editor with changes keeps the record: whatever would open another one asks first (§6.2, AC-7).
  const dirty = useRef(false);
  const [leaving, setLeaving] = useState<(() => void) | undefined>(undefined);
  const onDirtyChange = useCallback((value: boolean) => {
    dirty.current = value;
  }, []);
  const guard = useCallback((next: () => void) => {
    if (dirty.current) setLeaving(() => next);
    else next();
  }, []);

  const openRecord = useCallback(
    (id: string) => {
      if (id === search.id) return;
      guard(() => {
        setSearch({ id }, false);
      });
    },
    [guard, search.id, setSearch],
  );
  const setTab = (next: DlqTab) => {
    if (next === tab) return;
    guard(() => {
      setSearch({ tab: next === 'review' ? undefined : next, id: undefined, status: undefined }, false);
    });
  };

  // j / k move through the list; the detail follows (§6).
  useEffect(() => {
    if (tab === 'actions') return;
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target)) return;
      if (event.key !== 'j' && event.key !== 'k') return;
      const index = items.findIndex((item) => item.id === search.id);
      const next = items[event.key === 'j' ? Math.min(items.length - 1, index + 1) : Math.max(0, index - 1)];
      if (!next || next.id === search.id) return;
      event.preventDefault();
      openRecord(next.id);
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [tab, items, search.id, openRecord]);

  // Bulk actions (§6): the selection, the dialog, the progress and what failed.
  const [checked, setChecked] = useState<string[]>([]);
  const [rowErrors, setRowErrors] = useState<ReadonlyMap<string, unknown>>(new Map());
  const [bulkDialog, setBulkDialog] = useState<BulkKind | undefined>(undefined);
  const [progress, setProgress] = useState<{ done: number; total: number } | undefined>(undefined);
  const [choice, setChoice] = useState<DiscardReason>('duplicate');
  const [note, setNote] = useState('');
  const scope = JSON.stringify(filters) + tab;
  // A new tab or filter is a new list: nothing stays selected (compared during render, not in an effect).
  const [selectionScope, setSelectionScope] = useState(scope);
  if (selectionScope !== scope) {
    setSelectionScope(scope);
    setChecked([]);
    setRowErrors(new Map());
  }

  const runBulk = (kind: BulkKind) => {
    const ids = [...checked];
    setProgress({ done: 0, total: ids.length });
    void actions
      .bulk(kind, ids, {
        reason: kind === 'discard' ? discardReasonText(choice, note) : undefined,
        onProgress: (done) => {
          setProgress({ done, total: ids.length });
        },
      })
      .then((result) => {
        setProgress(undefined);
        // The ones that failed keep their selection and say why on the row (§6).
        setChecked(result.failed.map((failure) => failure.id));
        setRowErrors(new Map(result.failed.map((failure) => [failure.id, failure.error])));
        const text =
          kind === 'confirm'
            ? copy.bulk.confirmed(result.ok.length, result.failed.length)
            : kind === 'replay'
              ? copy.bulk.replayed(result.ok.length, result.failed.length, result.skipped)
              : copy.bulk.discarded(result.ok.length, result.failed.length, result.skipped);
        if (result.failed.length > 0) notify.warning(text);
        else notify.success(text);
      });
    return Promise.resolve();
  };
  const askBulk = (kind: BulkKind) => {
    if (kind === 'discard') {
      setChoice('duplicate');
      setNote('');
    }
    setBulkDialog(kind);
  };

  const header = (
    <div className="flex flex-col gap-3 px-4 pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h1 className="text-page font-semibold tracking-title">{copy.title}</h1>
          <p className="text-sm text-muted-foreground">
            {summary.data
              ? copy.summary(formatCount(summary.data.data.open), formatCount(summary.data.data.createdLastHour))
              : en.kv.empty}
          </p>
        </div>
        {access.role === 'viewer' ? <ReadOnly /> : null}
      </div>
      <div role="tablist" aria-label={copy.tabs.label} className="-mx-4 flex border-b border-border px-4">
        {TABS.map((value) => {
          const active = tab === value;
          const count = value === 'review' ? summary.data?.data.open : value === 'confirm' ? awaiting : undefined;
          return (
            <button
              key={value}
              type="button"
              role="tab"
              id={`dlq-tab-${value}`}
              aria-selected={active}
              aria-controls="dlq-panel"
              tabIndex={active ? 0 : -1}
              onClick={() => {
                setTab(value);
              }}
              onKeyDown={(event) => {
                const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
                if (step === 0) return;
                event.preventDefault();
                const next = TABS[(TABS.indexOf(value) + step + TABS.length) % TABS.length] ?? value;
                setTab(next);
                document.getElementById(`dlq-tab-${next}`)?.focus();
              }}
              className={cn(
                '-mb-px inline-flex h-10 items-center gap-1.5 border-b-2 px-2.5 text-sm font-medium whitespace-nowrap',
                active
                  ? 'border-foreground text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground',
              )}
            >
              {copy.tabs[value]}
              {count !== undefined ? (
                <span className="rounded-full bg-muted px-1.5 text-xs text-muted-foreground tabular-nums">
                  {formatCount(count)}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      <DlqFilters tab={tab} search={search} onSearch={setSearch} />
      <ConfirmHint
        tab={tab}
        waiting={awaiting}
        onReview={() => {
          setTab('confirm');
        }}
      />
    </div>
  );

  const clearFilters = () => {
    setSearch(clearedFilters(tab));
  };

  const dialogs = (
    <>
      <ConfirmDialog
        open={bulkDialog === 'confirm' || bulkDialog === 'replay'}
        title={bulkDialog === 'replay' ? copy.bulk.replayTitle(checked.length) : copy.bulk.confirmTitle(checked.length)}
        description={copy.bulk.confirmBody}
        confirmLabel={bulkDialog === 'replay' ? copy.bulk.replaySelected : copy.bulk.confirmReplay}
        onConfirm={() => runBulk(bulkDialog === 'replay' ? 'replay' : 'confirm')}
        onOpenChange={(next) => {
          if (!next) setBulkDialog(undefined);
        }}
      />
      <ConfirmDialog
        open={bulkDialog === 'discard'}
        tone="danger"
        title={copy.bulk.discardTitle(checked.length)}
        description={copy.dialogs.discardBody}
        confirmLabel={copy.buttons.discardRecord}
        valid={discardValid(choice, note)}
        onConfirm={() => runBulk('discard')}
        onOpenChange={(next) => {
          if (!next) setBulkDialog(undefined);
        }}
      >
        <DiscardFields choice={choice} note={note} onChoice={setChoice} onNote={setNote} />
      </ConfirmDialog>
      <ConfirmDialog
        open={leaving !== undefined}
        tone="danger"
        title={copy.dialogs.discardChangesTitle}
        description={copy.dialogs.discardChangesBody}
        confirmLabel={copy.dialogs.discardChanges}
        cancelLabel={copy.dialogs.keepEditing}
        onConfirm={() => {
          dirty.current = false;
          leaving?.();
          setLeaving(undefined);
          return Promise.resolve();
        }}
        onOpenChange={(next) => {
          if (!next) setLeaving(undefined);
        }}
      />
    </>
  );

  if (tab === 'actions') {
    return (
      <div className="flex flex-col gap-4">
        <section aria-label={copy.tabs.actions} className="rounded-lg border border-border bg-card shadow-sm">
          {header}
          <div id="dlq-panel" role="tabpanel" aria-labelledby="dlq-tab-actions" className="mt-3">
            <ActionLog
              query={log}
              items={entries}
              selectedId={search.id}
              filtered={filtered}
              onOpen={openRecord}
              onClearFilters={clearFilters}
            />
          </div>
        </section>
        <DetailDrawer
          title={search.id ? copy.detail : ''}
          open={search.id !== undefined}
          width={680}
          onClose={() => {
            guard(() => {
              setSearch({ id: undefined }, false);
            });
          }}
        >
          {search.id ? (
            <DlqDetail key={search.id} id={search.id} operator={operator} onDirtyChange={onDirtyChange} />
          ) : null}
        </DetailDrawer>
        {dialogs}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <SplitView
        listWidth={400}
        list={
          <section
            aria-label={copy.list}
            className="flex min-h-0 flex-col rounded-lg border border-border bg-card shadow-sm"
          >
            {header}
            <div id="dlq-panel" role="tabpanel" aria-labelledby={`dlq-tab-${tab}`} className="mt-3">
              <DlqList
                tab={tab}
                items={items}
                isPending={list.isPending}
                isError={list.isError}
                error={list.error}
                isPlaceholderData={list.isPlaceholderData}
                hasNextPage={list.hasNextPage}
                isFetchingNextPage={list.isFetchingNextPage}
                fetchNextPage={() => void list.fetchNextPage()}
                refetch={() => void list.refetch()}
                selectedId={search.id}
                onOpen={openRecord}
                operator={operator}
                checked={checked}
                onChecked={setChecked}
                rowErrors={rowErrors}
                filtered={filtered}
                onClearFilters={clearFilters}
                onViewClosed={() => {
                  setTab('closed');
                }}
              />
            </div>
            {checked.length > 0 || progress ? (
              <BulkBar
                tab={tab}
                count={checked.length}
                progress={progress}
                onAction={askBulk}
                onClear={() => {
                  setChecked([]);
                  setRowErrors(new Map());
                }}
              />
            ) : null}
          </section>
        }
        detail={
          search.id ? (
            <DlqDetail key={search.id} id={search.id} operator={operator} onDirtyChange={onDirtyChange} />
          ) : null
        }
        emptyDetail={<EmptyState title={copy.empty.select} />}
      />
      {dialogs}
    </div>
  );
}
