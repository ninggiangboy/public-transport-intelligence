import { useQuery } from '@tanstack/react-query';

import { isApiError } from '@/api/problem';
import type { Scenario, ScenarioRun } from '@/api/sim';
import { DetailDrawer } from '@/components/DetailDrawer';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { JsonViewer } from '@/components/JsonViewer';
import { KeyValueList } from '@/components/KeyValueList';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { requesterName } from '@/features/demo/model';
import { runQuery } from '@/features/demo/queries';
import { demoCopy } from '@/i18n/demo';
import { en } from '@/i18n/en';

const copy = demoCopy.drawer;

interface RunDrawerProps {
  runId: string;
  catalog: Scenario[] | undefined;
  stopping: boolean;
  onStop: (run: ScenarioRun) => void;
  onRunAgain: (scenario: Scenario, run: ScenarioRun) => void;
  onClose: () => void;
}

/** `?run=` (§4): a run's parameters, progress while it runs, times and requester; "Stop" and "Run again". */
export function RunDrawer({ runId, catalog, stopping, onStop, onRunAgain, onClose }: RunDrawerProps) {
  const detail = useQuery(runQuery(runId));
  const run = detail.data;
  const gone = isApiError(detail.error) && detail.error.status === 404;
  const scenario = catalog?.find((item) => item.name === run?.scenario);

  const title = (
    <span className="flex flex-wrap items-center gap-2">
      {run ? <StatusPill domain="scenarioRun" status={run.status} /> : null}
      <span>{copy.title(runId.slice(0, 8))}</span>
    </span>
  );

  const footer =
    run && (run.status === 'RUNNING' || scenario) ? (
      <div className="flex w-full flex-wrap items-center justify-end gap-2">
        {scenario ? (
          <Button
            variant="outline"
            onClick={() => {
              onRunAgain(scenario, run);
            }}
          >
            {copy.runAgain}
          </Button>
        ) : null}
        {run.status === 'RUNNING' ? (
          <Button
            variant="destructive"
            disabled={stopping}
            onClick={() => {
              onStop(run);
            }}
          >
            {demoCopy.running.stop}
          </Button>
        ) : null}
      </div>
    ) : undefined;

  return (
    <DetailDrawer title={title} open onClose={onClose} width={520} {...(footer ? { footer } : {})}>
      {gone ? (
        <EmptyState title={copy.gone} />
      ) : detail.isError && !run ? (
        <ErrorState error={detail.error} variant="block" panel={copy.panel} onRetry={() => void detail.refetch()} />
      ) : !run ? (
        <PanelSkeleton variant="detail" />
      ) : (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-0.5 text-sm">
            <p className="text-base font-semibold">{scenario?.title ?? run.scenario}</p>
            <p className="text-muted-foreground">{copy.requestedBy(requesterName(run.requestedBy))}</p>
          </div>
          <KeyValueList
            items={[
              { label: copy.started, value: <Timestamp at={run.startedAt} seconds /> },
              ...(run.plannedEndAt
                ? [{ label: copy.plannedEnd, value: <Timestamp at={run.plannedEndAt} seconds /> }]
                : []),
              { label: copy.ended, value: run.endedAt ? <Timestamp at={run.endedAt} seconds /> : en.kv.empty },
            ]}
          />
          <section className="flex flex-col gap-2">
            <h3 className="text-base font-semibold tracking-title">{copy.parameters}</h3>
            <JsonViewer value={run.params} wrap maxHeight={320} />
          </section>
          {run.status === 'RUNNING' && run.progress ? (
            <section className="flex flex-col gap-2">
              <h3 className="text-base font-semibold tracking-title">{copy.progress}</h3>
              <JsonViewer value={run.progress} wrap maxHeight={240} />
            </section>
          ) : null}
        </div>
      )}
    </DetailDrawer>
  );
}
