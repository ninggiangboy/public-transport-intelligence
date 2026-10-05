import { useCallback, useState } from 'react';

import { api, write } from '@/api/client';
import { isApiError } from '@/api/problem';
import { notify } from '@/lib/notify';

/** A restart or stop sent by this tab, watched through E-34 until the batch service answers (§5). */
export interface TrackedRequest {
  id: string;
  kind: 'RESTART' | 'STOP';
  /** The run it acts on. */
  runId: string;
  job: string;
  /** Wall clock when it was sent: after 60 s without an answer the screen says so. */
  sentAt: number;
}

export interface RunRequests {
  tracked: readonly TrackedRequest[];
  /** E-35, then watched; resolves once sent. 409 is a toast with the API's reason; other errors reject. */
  restart: (run: { runId: string; name: string }) => Promise<void>;
  /** E-36, likewise. */
  stop: (run: { runId: string; name: string }) => Promise<void>;
  /** The request in flight for a run, which turns its button into "Restart requested…". */
  pendingFor: (runId: string) => TrackedRequest['kind'] | undefined;
  done: (id: string) => void;
}

/** Restart and stop of the run drawer (§6); the watching outlives the drawer, so it lives on the page. */
export function useRunRequests(): RunRequests {
  const [tracked, setTracked] = useState<TrackedRequest[]>([]);

  const send = useCallback(async (kind: TrackedRequest['kind'], run: { runId: string; name: string }) => {
    const path = { params: { path: { runId: run.runId } } };
    try {
      const request = await write(
        kind === 'RESTART'
          ? api.POST('/api/v1/etl/jobs/{runId}/restart', path)
          : api.POST('/api/v1/etl/jobs/{runId}/stop', path),
      );
      setTracked((all) => [
        ...all.filter((item) => item.runId !== run.runId),
        { id: request.id, kind, runId: run.runId, job: run.name, sentAt: Date.now() },
      ]);
    } catch (error: unknown) {
      // job-not-restartable, job-not-running: the run changed under the operator; its detail says why (§6).
      if (isApiError(error) && error.status === 409) {
        notify.error(error.problem.detail ?? error.problem.title);
        return;
      }
      throw error;
    }
  }, []);

  const restart = useCallback((run: { runId: string; name: string }) => send('RESTART', run), [send]);
  const stop = useCallback((run: { runId: string; name: string }) => send('STOP', run), [send]);
  const pendingFor = useCallback((runId: string) => tracked.find((item) => item.runId === runId)?.kind, [tracked]);
  const done = useCallback((id: string) => {
    setTracked((all) => all.filter((item) => item.id !== id));
  }, []);

  return { tracked, restart, stop, pendingFor, done };
}
