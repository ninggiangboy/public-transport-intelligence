import type { UseQueryResult } from '@tanstack/react-query';
import { useMemo } from 'react';

import type { Scenario, ScenarioRun } from '@/api/sim';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { paramsSummary, requesterName } from '@/features/demo/model';
import { demoCopy } from '@/i18n/demo';
import { en } from '@/i18n/en';

const copy = demoCopy.history;
/** The parameters are cut in the table; the tooltip and the drawer have them whole. */
const SUMMARY_CHARS = 70;

interface RunHistoryProps {
  runs: UseQueryResult<ScenarioRun[]>;
  catalog: Scenario[] | undefined;
  selectedId: string | undefined;
  onOpen: (runId: string) => void;
}

/** "History" (§4): the latest runs, newest first, whoever started them; a row opens the drawer. */
export function RunHistory({ runs, catalog, selectedId, onOpen }: RunHistoryProps) {
  const columns = useMemo(() => historyColumns(catalog), [catalog]);
  const items = runs.data ?? [];
  return (
    <section aria-labelledby="demo-history" className="flex flex-col gap-2.5">
      <h2 id="demo-history" className="text-[17px] leading-tight font-semibold tracking-title">
        {copy.heading}
      </h2>
      <div className="rounded-lg border border-border bg-card shadow-sm">
        {runs.isPending ? (
          <PanelSkeleton variant="table" rows={5} />
        ) : runs.isError && items.length === 0 ? (
          <ErrorState error={runs.error} variant="block" panel={copy.heading} onRetry={() => void runs.refetch()} />
        ) : (
          <>
            {runs.isError ? (
              <ErrorState
                error={runs.error}
                variant="inline"
                panel={copy.heading}
                onRetry={() => void runs.refetch()}
              />
            ) : null}
            <DataTable
              columns={columns}
              data={items}
              getRowId={(run) => run.runId}
              selectedId={selectedId}
              onRowOpen={(run) => {
                onOpen(run.runId);
              }}
              caption={copy.caption(items.length)}
              density="compact"
              empty={<EmptyState title={copy.empty} />}
            />
          </>
        )}
      </div>
    </section>
  );
}

function historyColumns(catalog: Scenario[] | undefined) {
  const col = columnHelper<ScenarioRun>();
  const c = copy.columns;
  const scenarioOf = (name: string) => catalog?.find((item) => item.name === name);
  return [
    col.accessor('scenario', {
      header: c.scenario,
      cell: (info) => scenarioOf(info.getValue())?.title ?? info.getValue(),
      meta: { className: 'whitespace-nowrap font-medium' },
    }),
    col.accessor('status', {
      header: c.status,
      cell: (info) => <StatusPill domain="scenarioRun" status={info.getValue()} size="sm" />,
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('startedAt', {
      header: c.started,
      cell: (info) => <Timestamp at={info.getValue()} showZone={false} />,
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('endedAt', {
      header: c.ended,
      cell: (info) => {
        const ended = info.getValue();
        return ended ? <Timestamp at={ended} showZone={false} /> : en.kv.empty;
      },
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('requestedBy', {
      header: c.requestedBy,
      cell: (info) => requesterName(info.getValue()),
      meta: { className: 'whitespace-nowrap' },
    }),
    col.display({
      id: 'parameters',
      header: c.parameters,
      cell: (info) => {
        const run = info.row.original;
        const summary = paramsSummary(scenarioOf(run.scenario), run.params);
        return (
          <span title={JSON.stringify(run.params, null, 2)} className="text-xs text-muted-foreground">
            {summary.length > SUMMARY_CHARS ? `${summary.slice(0, SUMMARY_CHARS)}…` : summary || en.kv.empty}
          </span>
        );
      },
    }),
  ];
}
