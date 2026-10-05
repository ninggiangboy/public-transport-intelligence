import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

import { keys } from '@/api/keys';
import { jobRequestQuery } from '@/features/ops-jobs/queries';
import type { TrackedRequest } from '@/features/ops-jobs/use-run-requests';
import { pipelineCopy } from '@/i18n/pipeline';
import { notify } from '@/lib/notify';

const copy = pipelineCopy.pipeline;
/** Without an answer after this long, the screen says the batch service has not picked the request up (§6). */
export const PICKUP_TIMEOUT_MS = 60_000;

interface RequestWatcherProps {
  request: TrackedRequest;
  onOpenRun: (runId: string) => void;
  onDone: (id: string) => void;
}

/**
 * Polls E-34 every 2 s for one restart or stop and says how it ended (§6): "Restarted as run #…" with "Open",
 * "… was rejected: {message}", or after 60 s "The batch service hasn't picked up the request yet." Renders nothing.
 */
export function RequestWatcher({ request, onOpenRun, onDone }: RequestWatcherProps) {
  const queryClient = useQueryClient();
  const answer = useQuery(jobRequestQuery(request.id));
  const status = answer.data?.data.status;
  const runId = answer.data?.data.runId;
  const jobExecutionId = answer.data?.data.jobExecutionId;
  const message = answer.data?.data.message;

  useEffect(() => {
    if (status === undefined || status === 'PENDING') return;
    void queryClient.invalidateQueries({ queryKey: keys.etl.job(request.runId) });
    void queryClient.invalidateQueries({ queryKey: keys.etl.jobs.all() });
    if (status === 'REJECTED' || status === 'FAILED') {
      const reason = message ?? status;
      notify.error(
        request.kind === 'RESTART' ? copy.feedback.restartRejected(reason) : copy.feedback.stopRejected(reason),
      );
    } else if (request.kind === 'RESTART' && runId && runId !== request.runId) {
      notify.success(copy.feedback.restarted(String(jobExecutionId ?? runId)), {
        action: {
          label: copy.actions.open,
          onClick: () => {
            onOpenRun(runId);
          },
        },
      });
    }
    onDone(request.id);
  }, [status, runId, jobExecutionId, message, request, queryClient, onOpenRun, onDone]);

  useEffect(() => {
    const timer = setTimeout(
      () => {
        notify.warning(copy.feedback.notPickedUp);
        onDone(request.id);
      },
      Math.max(0, request.sentAt + PICKUP_TIMEOUT_MS - Date.now()),
    );
    return () => {
      clearTimeout(timer);
    };
  }, [request, onDone]);

  return null;
}
