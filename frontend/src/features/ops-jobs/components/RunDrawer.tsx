import { useQuery } from '@tanstack/react-query';
import { LoaderCircle } from 'lucide-react';
import { useState } from 'react';

import type { components } from '@/api/generated/schema';
import { isApiError } from '@/api/problem';
import { ActivityTimeline } from '@/components/ActivityTimeline';
import { AppLink } from '@/components/AppLink';
import { Callout } from '@/components/Callout';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { CopyButton } from '@/components/CopyButton';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { DetailDrawer } from '@/components/DetailDrawer';
import { Duration } from '@/components/Duration';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { JsonViewer } from '@/components/JsonViewer';
import { KeyValueList } from '@/components/KeyValueList';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { statusVisual } from '@/components/status-map';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { GrafanaLinks, LiveDuration } from '@/features/ops-jobs/components/parts';
import {
  canStop,
  CONTEXT_LIMIT,
  exitTone,
  firstLine,
  formatOffsets,
  HREF,
  isBatchJob,
  streamMinute,
  triggerLabel,
  type JobRunDetail,
} from '@/features/ops-jobs/model';
import { jobRequestQuery, runQuery } from '@/features/ops-jobs/queries';
import type { RunRequests } from '@/features/ops-jobs/use-run-requests';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { useBusinessClock } from '@/lib/business-clock';
import { actorName, formatCount } from '@/lib/format';
import { formatTime } from '@/lib/time';
import { useReducedMotion } from '@/lib/use-reduced-motion';
import { cn } from '@/lib/utils';
import { useRealtimeState } from '@/realtime/useRealtime';

type Schemas = components['schemas'];
type Step = Schemas['StepResponse'];
type MicroBatch = Schemas['StreamBatchResponse'];
type Parameter = Schemas['ParameterResponse'];

const copy = pipelineCopy.pipeline;
const drawer = copy.drawer;
const statusLabels: Record<string, string> = en.status.job;

interface RunDrawerProps {
  runId: string;
  operator: boolean;
  requests: RunRequests;
  onClose: () => void;
}

/** The run number of a batch job, or the listener and minute of a stream run (§6.1). */
function runLabel(run: Pick<JobRunDetail, 'kind' | 'runId' | 'name' | 'jobExecutionId'>, timeZone: string) {
  if (isBatchJob(run)) return copy.runNumber(run.jobExecutionId ?? run.runId);
  const minute = streamMinute(run.runId);
  return minute ? `${run.name} · ${formatTime(minute, { timeZone, showZone: false })}` : run.name;
}

/** The run of `?run=` (§6.1): what happened, its steps or micro-batches, and restart or stop for an operator. */
export function RunDrawer({ runId, operator, requests, onClose }: RunDrawerProps) {
  const clock = useBusinessClock();
  const realtime = useRealtimeState();
  const [confirm, setConfirm] = useState<'restart' | 'stop' | undefined>(undefined);
  const detail = useQuery(runQuery(runId, realtime.status !== 'open'));
  const run = detail.data?.data;
  const gone = isApiError(detail.error) && detail.error.status === 404;
  const pending = requests.pendingFor(runId);

  const title = run ? (
    <span className="flex flex-wrap items-center gap-2">
      <StatusPill domain="job" status={run.status} />
      <span>{runLabel(run, clock.timezone)}</span>
    </span>
  ) : (
    copy.panels.run
  );

  const footer = run ? (
    <div className="flex w-full flex-wrap items-center gap-2">
      <GrafanaLinks {...(run.links ? { links: run.links } : {})} />
      <CopyButton value={run.runId} label={copy.actions.copyRunId}>
        {copy.actions.copyRunId}
      </CopyButton>
      <span className="flex-1" />
      {operator && isBatchJob(run) && canStop(run.status) ? (
        <Button
          variant="outline"
          disabled={pending !== undefined}
          onClick={() => {
            setConfirm('stop');
          }}
        >
          {pending === 'STOP' ? <Pending label={copy.actions.stopRequested} /> : copy.actions.stop}
        </Button>
      ) : null}
      {operator && isBatchJob(run) && run.restartable ? (
        <Button
          disabled={pending !== undefined}
          onClick={() => {
            setConfirm('restart');
          }}
        >
          {pending === 'RESTART' ? <Pending label={copy.actions.restartRequested} /> : copy.actions.restart}
        </Button>
      ) : null}
    </div>
  ) : undefined;

  return (
    <DetailDrawer title={title} open onClose={onClose} width={680} {...(footer ? { footer } : {})}>
      {gone ? (
        <EmptyState title={drawer.gone} description={drawer.goneBody} />
      ) : detail.isError && !run ? (
        <ErrorState
          error={detail.error}
          variant="block"
          panel={copy.panels.run}
          onRetry={() => void detail.refetch()}
        />
      ) : !run ? (
        <PanelSkeleton variant="detail" />
      ) : (
        <RunBody run={run} />
      )}
      {run ? (
        <>
          <ConfirmDialog
            open={confirm === 'restart'}
            title={copy.confirm.restartTitle(run.name, String(run.jobExecutionId ?? run.runId))}
            description={copy.confirm.restartBody}
            confirmLabel={copy.actions.restart}
            onConfirm={() => requests.restart(run)}
            onOpenChange={(open) => {
              if (!open) setConfirm(undefined);
            }}
          />
          <ConfirmDialog
            open={confirm === 'stop'}
            title={copy.confirm.stopTitle(run.name, String(run.jobExecutionId ?? run.runId))}
            description={copy.confirm.stopBody}
            confirmLabel={copy.actions.stop}
            tone="danger"
            onConfirm={() => requests.stop(run)}
            onOpenChange={(open) => {
              if (!open) setConfirm(undefined);
            }}
          />
        </>
      ) : null}
    </DetailDrawer>
  );
}

function Pending({ label }: { label: string }) {
  const reducedMotion = useReducedMotion();
  return (
    <>
      <LoaderCircle className={cn(!reducedMotion && 'animate-spin')} aria-hidden="true" />
      {label}
    </>
  );
}

function RunBody({ run }: { run: JobRunDetail }) {
  const tone = exitTone(run.status);
  const status = statusLabels[run.status] ?? run.status;
  return (
    <div className="flex flex-col gap-5">
      <div>
        <p className="font-mono text-sm font-semibold">{run.name}</p>
        <p className="mt-0.5 text-sm text-muted-foreground">
          {triggerLabel(run)}
          {run.startedAt ? (
            <>
              {' · '}
              <Timestamp at={run.startedAt} format="time" seconds showZone={false} />
            </>
          ) : null}
          {' · '}
          <LiveDuration run={run} />
        </p>
      </div>

      {tone ? (
        <Callout tone={tone} title={firstLine(run.exitMessage) ?? status}>
          <span className="break-words">
            {run.exitMessage
              ? drawer.exit(run.exitCode ?? run.status, run.exitMessage)
              : drawer.exitCode(run.exitCode ?? run.status)}
          </span>
        </Callout>
      ) : null}

      {isBatchJob(run) ? (
        <>
          <Steps steps={run.steps ?? []} />
          <Parameters parameters={run.parameters ?? []} />
          <Origin request={run.request} />
        </>
      ) : (
        <MicroBatches batches={run.batches ?? []} />
      )}
    </div>
  );
}

function SectionTitle({ children }: { children: string }) {
  return <h3 className="mb-2 text-base font-semibold tracking-title">{children}</h3>;
}

function Steps({ steps }: { steps: Step[] }) {
  if (steps.length === 0) return null;
  return (
    <section>
      <SectionTitle>{drawer.steps}</SectionTitle>
      <ActivityTimeline
        items={steps.map((step) => ({
          id: String(step.stepExecutionId),
          at: step.startedAt ?? step.endedAt ?? '',
          axis: 'audit' as const,
          tone: statusVisual('job', step.status)?.tone ?? 'neutral',
          text: <StepText step={step} />,
        }))}
      />
    </section>
  );
}

function StepText({ step }: { step: Step }) {
  const context = step.executionContext;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono font-medium">{step.stepName}</span>
        <StatusPill domain="job" status={step.status} size="sm" />
        {step.startedAt ? (
          <span className="text-xs text-muted-foreground">
            <LiveDuration run={{ startedAt: step.startedAt, endedAt: step.endedAt, status: step.status }} />
          </span>
        ) : null}
      </div>
      <p className="text-xs text-muted-foreground tabular-nums">
        {drawer.step.counts(formatCount(step.readCount), formatCount(step.writeCount), formatCount(step.filterCount))}
      </p>
      <p className="text-xs text-muted-foreground tabular-nums">
        {drawer.step.skips(
          formatCount(step.readSkipCount),
          formatCount(step.processSkipCount),
          formatCount(step.writeSkipCount),
        )}
        {' · '}
        {drawer.step.commits(formatCount(step.commitCount), formatCount(step.rollbackCount))}
      </p>
      {step.batchId ? (
        <p className="text-xs">
          <span className="text-muted-foreground">{drawer.step.batch} </span>
          <AppLink href={HREF.batch(step.batchId)} className="font-mono text-primary hover:underline">
            {step.batchId.slice(0, 8)}
          </AppLink>
        </p>
      ) : null}
      {context ? (
        <details className="group mt-1">
          <summary className="cursor-pointer text-xs font-medium text-foreground-2 select-none">
            {drawer.executionContext}
          </summary>
          <div className="mt-2 flex flex-col gap-1">
            <JsonViewer value={context} maxHeight={220} wrap fileName={drawer.executionContext} />
            {context.length >= CONTEXT_LIMIT ? (
              <p className="text-xs text-muted-foreground">{drawer.truncated}</p>
            ) : null}
          </div>
        </details>
      ) : null}
    </div>
  );
}

function Parameters({ parameters }: { parameters: Parameter[] }) {
  if (parameters.length === 0) return null;
  return (
    <section>
      <SectionTitle>{drawer.parameters}</SectionTitle>
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full text-sm">
          <caption className="sr-only">{drawer.parametersCaption}</caption>
          <thead className="bg-surface text-left text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="px-3 py-2 font-medium">
                {drawer.parameter.name}
              </th>
              <th scope="col" className="px-3 py-2 font-medium">
                {drawer.parameter.value}
              </th>
              <th scope="col" className="px-3 py-2 font-medium">
                {drawer.parameter.type}
              </th>
            </tr>
          </thead>
          <tbody>
            {parameters.map((parameter) => (
              <tr key={parameter.name} className="border-t border-border">
                <td className="px-3 py-2 font-mono text-xs whitespace-nowrap">
                  {parameter.name}
                  {parameter.identifying ? (
                    <span title={drawer.identifyingMark} aria-label={drawer.identifyingMark}>
                      *
                    </span>
                  ) : null}
                </td>
                <td className="px-3 py-2 font-mono text-xs break-all">{parameter.value}</td>
                <td className="px-3 py-2 text-xs whitespace-nowrap text-muted-foreground">
                  {parameter.type.replace(/^java\.(lang|time|util)\./, '')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {parameters.some((parameter) => parameter.identifying) ? (
        <p className="mt-1.5 text-xs text-muted-foreground">{drawer.identifying}</p>
      ) : null}
    </section>
  );
}

function Origin({ request }: { request?: JobRunDetail['request'] }) {
  return (
    <section>
      <SectionTitle>{drawer.origin}</SectionTitle>
      {!request ? (
        <p className="text-sm text-muted-foreground">{drawer.scheduled}</p>
      ) : request.type === 'replay' ? (
        <AppLink href={HREF.replay(request.id)} className="text-sm text-primary hover:underline">
          {drawer.startedByReplay(request.id.slice(0, 8))}
        </AppLink>
      ) : (
        <JobRequestPopover id={request.id} />
      )}
    </section>
  );
}

/** "Started by job request …": who asked, when and with what (E-34), fetched when opened (§6). */
function JobRequestPopover({ id }: { id: string }) {
  const [open, setOpen] = useState(false);
  const request = useQuery({ ...jobRequestQuery(id), enabled: open, refetchInterval: false });
  const data = request.data?.data;
  const parameters = Object.entries(data?.parameters ?? {});
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className="text-sm text-primary hover:underline">
          {drawer.startedByRequest(id.slice(0, 8))}
        </button>
      </PopoverTrigger>
      <PopoverContent aria-label={drawer.request.title} className="w-80">
        {!data ? (
          request.isError ? (
            <ErrorState error={request.error} variant="block" onRetry={() => void request.refetch()} />
          ) : (
            <PanelSkeleton variant="list" rows={3} />
          )
        ) : (
          <KeyValueList
            items={[
              { label: drawer.request.requestedBy, value: actorName(data.requestedBy) },
              { label: drawer.request.requestedAt, value: <Timestamp at={data.requestedAt} seconds /> },
              { label: drawer.request.kind, value: data.kind },
              { label: drawer.request.status, value: <StatusPill domain="jobRequest" status={data.status} /> },
              {
                label: drawer.request.parameters,
                value:
                  parameters.length === 0 ? (
                    drawer.request.none
                  ) : (
                    <span className="font-mono text-xs break-all">
                      {parameters.map(([name, value]) => `${name}=${value}`).join(', ')}
                    </span>
                  ),
              },
            ]}
          />
        )}
      </PopoverContent>
    </Popover>
  );
}

const batchColumns = (() => {
  const col = columnHelper<MicroBatch>();
  const cols = drawer.batchColumns;
  return [
    col.accessor('batchId', {
      header: cols.batch,
      cell: (info) => (
        <AppLink href={HREF.batch(info.getValue())} className="font-mono text-xs text-primary hover:underline">
          {info.getValue().slice(0, 8)}
        </AppLink>
      ),
    }),
    col.accessor('status', {
      header: cols.status,
      cell: (info) => <StatusPill domain="job" status={info.getValue()} size="sm" />,
    }),
    col.accessor('writeMode', {
      header: cols.writeMode,
      cell: (info) => (pipelineCopy.writeMode as Record<string, string>)[info.getValue()] ?? info.getValue(),
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('instanceId', {
      header: cols.instance,
      cell: (info) => <span className="font-mono text-xs whitespace-nowrap">{info.getValue()}</span>,
    }),
    col.accessor('offsets', {
      header: cols.offsets,
      cell: (info) => <span className="font-mono text-xs whitespace-nowrap">{formatOffsets(info.getValue())}</span>,
    }),
    col.accessor('recordsRead', {
      header: cols.read,
      cell: (info) => formatCount(info.getValue()),
      meta: { align: 'right' },
    }),
    col.accessor('recordsWritten', {
      header: cols.written,
      cell: (info) => formatCount(info.getValue()),
      meta: { align: 'right' },
    }),
    col.accessor('recordsSkipped', {
      header: cols.skipped,
      cell: (info) => formatCount(info.getValue()),
      meta: { align: 'right' },
    }),
    col.accessor('recordsDuplicate', {
      header: cols.duplicates,
      cell: (info) => formatCount(info.getValue()),
      meta: { align: 'right' },
    }),
    col.display({
      id: 'eventTime',
      header: cols.eventTime,
      cell: (info) => {
        const { minEventTs, maxEventTs } = info.row.original;
        if (!minEventTs || !maxEventTs) return en.kv.empty;
        return (
          <span className="whitespace-nowrap">
            <Timestamp at={minEventTs} format="time" seconds showZone={false} />
            {' – '}
            <Timestamp at={maxEventTs} format="time" seconds showZone={false} />
          </span>
        );
      },
    }),
    col.display({
      id: 'duration',
      header: cols.duration,
      cell: (info) => (
        <Duration ms={Date.parse(info.row.original.finishedAt) - Date.parse(info.row.original.startedAt)} />
      ),
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('errorClass', {
      header: cols.error,
      cell: (info) => {
        const errorClass = info.getValue();
        if (!errorClass) return en.kv.empty;
        return (
          <span title={info.row.original.errorMessage} className="font-mono text-xs text-tone-danger-fg">
            {errorClass.split('.').at(-1)}
          </span>
        );
      },
    }),
    col.display({
      id: 'links',
      header: cols.links,
      cell: (info) => (
        <span className="flex gap-2 text-xs whitespace-nowrap">
          <a
            href={info.row.original.links.trace}
            target="_blank"
            rel="noreferrer noopener"
            className="text-primary hover:underline"
          >
            {copy.actions.trace}
          </a>
          <a
            href={info.row.original.links.logs}
            target="_blank"
            rel="noreferrer noopener"
            className="text-primary hover:underline"
          >
            {copy.actions.logs}
          </a>
        </span>
      ),
    }),
  ];
})();

function MicroBatches({ batches }: { batches: MicroBatch[] }) {
  return (
    <section>
      <SectionTitle>{drawer.microBatches}</SectionTitle>
      <div className="-mx-5 border-y border-border">
        <DataTable
          columns={batchColumns}
          data={batches}
          getRowId={(batch) => batch.batchId}
          caption={drawer.microBatchesCaption(batches.length)}
          density="compact"
          empty={<EmptyState title={drawer.noMicroBatches} />}
        />
      </div>
    </section>
  );
}
