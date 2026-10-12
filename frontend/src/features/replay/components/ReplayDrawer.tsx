import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { isApiError } from '@/api/problem';
import { AppLink } from '@/components/AppLink';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { CopyButton } from '@/components/CopyButton';
import { DetailDrawer } from '@/components/DetailDrawer';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RelativeTime } from '@/components/RelativeTime';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import type { RunRequests } from '@/features/ops-jobs/use-run-requests';
import {
  HREF,
  isActive,
  progressPercent,
  readStats,
  resolvedHref,
  runNumber,
  STAT_KEYS,
  type Replay,
} from '@/features/replay/model';
import { estimateQuery, replayQuery } from '@/features/replay/queries';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { replayCopy } from '@/i18n/replay';
import { useBusinessClock } from '@/lib/business-clock';
import { actorName, formatCompact, formatCount, formatDuration } from '@/lib/format';
import { formatDateTime } from '@/lib/time';

const copy = replayCopy.drawer;
const sourceLabels: Partial<Record<string, string>> = en.source;
/** The job behind every raw zone and dead-letter replay (DOC-22 §3, §4). */
const REPLAY_JOB = 'RawZoneReplayJob';

interface ReplayDrawerProps {
  id: string;
  operator: boolean;
  requests: RunRequests;
  onReplayAgain: (replay: Replay) => void;
  onClose: () => void;
}

/** `?replay=` (§6.1): progress while it runs, statistics once it ends, restart and "Replay again" for an operator. */
export function ReplayDrawer({ id, operator, requests, onReplayAgain, onClose }: ReplayDrawerProps) {
  const detail = useQuery(replayQuery(id));
  const replay = detail.data?.data;
  const gone = isApiError(detail.error) && detail.error.status === 404;
  const [restarting, setRestarting] = useState(false);
  const runId = replay?.runId;
  const pending = runId ? requests.pendingFor(runId) : undefined;

  const title = replay ? (
    <span className="flex flex-wrap items-center gap-2">
      <StatusPill domain="replay" status={replay.status} />
      <span>{copy.title(replay.id.slice(0, 8))}</span>
      <CopyButton value={replay.id} label={copy.copyId} />
    </span>
  ) : (
    replayCopy.title
  );

  const canRestart = operator && replay?.status === 'FAILED' && runId !== undefined;
  const canReplayAgain = operator && replay?.kind === 'RAW_RANGE' && replay.fromTs && replay.toTs;
  const footer =
    replay && (canRestart || canReplayAgain) ? (
      <div className="flex w-full flex-wrap items-center justify-end gap-2">
        {canReplayAgain ? (
          <Button
            variant="outline"
            onClick={() => {
              onReplayAgain(replay);
            }}
          >
            {copy.replayAgain}
          </Button>
        ) : null}
        {canRestart ? (
          <Button
            disabled={pending !== undefined}
            onClick={() => {
              setRestarting(true);
            }}
          >
            {pending === 'RESTART' ? pipelineCopy.pipeline.actions.restartRequested : copy.restart}
          </Button>
        ) : null}
      </div>
    ) : undefined;

  return (
    <DetailDrawer title={title} open onClose={onClose} width={520} {...(footer ? { footer } : {})}>
      {gone ? (
        <EmptyState title={copy.gone} />
      ) : detail.isError && !replay ? (
        <ErrorState error={detail.error} variant="block" panel={copy.panel} onRetry={() => void detail.refetch()} />
      ) : !replay ? (
        <PanelSkeleton variant="detail" />
      ) : (
        <ReplayBody replay={replay} operator={operator} />
      )}
      {replay && runId ? (
        <ConfirmDialog
          open={restarting}
          title={pipelineCopy.pipeline.confirm.restartTitle(REPLAY_JOB, runNumber(replay) ?? runId)}
          description={pipelineCopy.pipeline.confirm.restartBody}
          confirmLabel={copy.restart}
          onConfirm={() => requests.restart({ runId, name: REPLAY_JOB })}
          onOpenChange={(open) => {
            if (!open) setRestarting(false);
          }}
        />
      ) : null}
    </DetailDrawer>
  );
}

function ReplayBody({ replay, operator }: { replay: Replay; operator: boolean }) {
  const clock = useBusinessClock();
  const timeZone = clock.timezone;
  const source = sourceLabels[replay.source] ?? replay.source;
  const run = runNumber(replay);
  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-0.5 text-sm">
        <p className="font-medium">
          {replayCopy.kind[replay.kind] ?? replay.kind}
          {' · '}
          {source}
        </p>
        {replay.fromTs && replay.toTs ? (
          <p>
            {formatDateTime(replay.fromTs, { timeZone, showZone: false })}
            {' → '}
            {formatDateTime(replay.toTs, { timeZone })}
          </p>
        ) : null}
        <p className="text-muted-foreground">
          {copy.requested(
            actorName(replay.requestedBy),
            formatDateTime(replay.requestedAt, { timeZone, showZone: false }),
          )}
        </p>
        <p className="text-muted-foreground">
          {copy.recompute(replay.recomputeAnalytics ? replayCopy.yes : replayCopy.no)}
        </p>
      </div>

      {isActive(replay.status) ? <Running replay={replay} operator={operator} /> : <Finished replay={replay} />}

      <div className="flex flex-col gap-1.5 text-sm">
        {replay.kind === 'DLQ_RECORD' && replay.deadLetterId ? (
          <AppLink href={HREF.deadLetter(replay.deadLetterId)} className="text-primary hover:underline">
            {copy.deadLetter(replay.deadLetterId.slice(0, 8))}
          </AppLink>
        ) : null}
        {replay.runId && run ? (
          <AppLink href={HREF.run(replay.runId)} className="text-primary hover:underline">
            {copy.run(run)} →
          </AppLink>
        ) : null}
      </div>
    </div>
  );
}

/** Step, a bar against the E-53 estimate (indeterminate without one), and the counters (§6.1). */
function Running({ replay, operator }: { replay: Replay; operator: boolean }) {
  const progress = replay.progress;
  const range = replay.kind === 'RAW_RANGE' && replay.fromTs !== undefined && replay.toTs !== undefined;
  // Asked once when the drawer opens; E-53 is for operators only.
  const estimate = useQuery({
    ...estimateQuery({ source: replay.source, fromTs: replay.fromTs ?? '', toTs: replay.toTs ?? '' }),
    staleTime: Number.POSITIVE_INFINITY,
    enabled: range && operator,
  });
  const total = estimate.data?.data.estimatedMessages;
  const read = progress?.readCount ?? 0;
  const percent = progressPercent(read, total);
  return (
    <div className="flex flex-col gap-2">
      {progress ? <p className="text-sm">{copy.step(progress.step)}</p> : null}
      <div className="flex items-center gap-3">
        <Progress aria-label={copy.progressLabel} value={percent ?? null} className="flex-1" />
        {percent !== undefined ? <span className="text-sm tabular-nums">~{percent}%</span> : null}
      </div>
      {progress ? (
        <>
          <p className="text-sm tabular-nums">
            {total !== undefined ? copy.of(formatCompact(read), formatCompact(total)) : copy.read(formatCount(read))}
          </p>
          <p className="text-sm text-muted-foreground tabular-nums">
            {copy.counts(formatCompact(progress.writeCount), formatCount(progress.skipCount))}
            {progress.updatedAt ? (
              <>
                {copy.updated}
                <RelativeTime at={progress.updatedAt} axis="audit" />
              </>
            ) : null}
          </p>
        </>
      ) : null}
    </div>
  );
}

/** When it ended, its message and the statistics of DOC-22 §4.6 (§6.1, AC-5). */
function Finished({ replay }: { replay: Replay }) {
  const clock = useBusinessClock();
  const stats = readStats(replay.stats);
  const resolved = stats?.counts.dlqResolved ?? 0;
  const ended = replay.finishedAt;
  return (
    <div className="flex flex-col gap-3">
      {ended ? (
        <p className="text-sm">
          {copy.finished(
            formatDateTime(ended, { timeZone: clock.timezone }),
            replay.startedAt ? formatDuration(Date.parse(ended) - Date.parse(replay.startedAt)) : en.kv.empty,
          )}
        </p>
      ) : null}
      {replay.message ? (
        <p className={replay.status === 'FAILED' ? 'text-sm text-tone-danger-fg' : 'text-sm text-muted-foreground'}>
          {replay.message}
        </p>
      ) : null}
      {stats ? (
        <section>
          <h3 className="mb-2 text-base font-semibold tracking-title">{copy.stats}</h3>
          <table className="w-full text-sm">
            <caption className="sr-only">{copy.statsCaption}</caption>
            <tbody>
              {STAT_KEYS.filter((key) => stats.counts[key] !== undefined).map((key) => (
                <tr key={key} className="border-t border-border first:border-t-0">
                  <th scope="row" className="py-1.5 pr-3 text-left font-normal text-muted-foreground">
                    {copy.stat[key]}
                  </th>
                  <td className="py-1.5 text-right tabular-nums">{formatCount(stats.counts[key] ?? 0)}</td>
                </tr>
              ))}
              {stats.minEventTs && stats.maxEventTs ? (
                <tr className="border-t border-border">
                  <th scope="row" className="py-1.5 pr-3 text-left font-normal text-muted-foreground">
                    {copy.stat.eventTime}
                  </th>
                  <td className="py-1.5 text-right">
                    <Timestamp at={stats.minEventTs} showZone={false} />
                    {' → '}
                    <Timestamp at={stats.maxEventTs} />
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
          {stats.analytics.length > 0 ? (
            <ul className="mt-2 flex flex-col gap-1 text-sm">
              {stats.analytics.map((item) => (
                <li key={item.detector}>
                  {copy.detector(
                    item.detector,
                    formatCount(item.scopes),
                    formatCount(item.upserted),
                    formatCount(item.deleted),
                  )}
                </li>
              ))}
            </ul>
          ) : null}
        </section>
      ) : null}
      {resolved > 0 ? (
        <AppLink href={resolvedHref(replay)} className="text-sm text-primary hover:underline">
          {copy.viewResolved}
        </AppLink>
      ) : null}
    </div>
  );
}
