import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useRouter } from '@tanstack/react-router';
import { useCallback, useMemo } from 'react';

import { api, newIdempotencyKey, write } from '@/api/client';
import type { WithAsOf } from '@/api/client';
import { isApiError } from '@/api/problem';
import { keys } from '@/api/keys';
import { cachedStatus, patchDeadLetter, setStatus } from '@/features/dlq/cache';
import { accepts, runBulk, type BulkKind, type BulkOutcome, type DeadLetterDetail } from '@/features/dlq/model';
import { HREF } from '@/features/ops-jobs/model';
import { dlqCopy } from '@/i18n/dlq';
import { notify } from '@/lib/notify';
import { describeError } from '@/lib/problem-copy';

const toast = dlqCopy.dlq.toast;

/** What each dialog and bulk action sends: the keys a retry reuses (DOC-31 §8). */
export interface ReplayCall {
  kind: 'replay' | 'confirm';
  key: string;
}

function refresh(queryClient: QueryClient, id: string) {
  void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.detail(id) });
  void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.summary() });
  void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.listAll() });
}

/** Sends E-44 or E-45 with the key of this action; the status is `REPLAY_REQUESTED` before the answer (§6). */
async function sendReplay(queryClient: QueryClient, id: string, call: ReplayCall) {
  const rollback = setStatus(queryClient, id, 'REPLAY_REQUESTED');
  const options = { params: { path: { id }, header: { 'Idempotency-Key': call.key } } };
  try {
    return await write(
      call.kind === 'confirm'
        ? api.POST('/api/v1/etl/dlq/{id}/confirm', options)
        : api.POST('/api/v1/etl/dlq/{id}/replay', options),
    );
  } catch (error: unknown) {
    rollback();
    throw error;
  }
}

/** E-46. */
async function sendDiscard(queryClient: QueryClient, id: string, reason: string) {
  const rollback = setStatus(queryClient, id, 'DISCARDED');
  try {
    await write(api.POST('/api/v1/etl/dlq/{id}/discard', { params: { path: { id } }, body: { reason } }));
  } catch (error: unknown) {
    rollback();
    throw error;
  }
}

export interface BulkResult extends BulkOutcome {
  skipped: number;
}

/** The writes of the Dead letters screen, with their optimistic updates and the feedback of §6. */
export function useDlqActions() {
  const queryClient = useQueryClient();
  const router = useRouter();

  const openReplay = useCallback(
    (replayId: string) => {
      router.history.push(HREF.replay(replayId));
    },
    [router],
  );

  return useMemo(() => {
    /**
     * E-44 / E-45 for one record. Never rejects: a refused request is undone and told (409 `dlq-invalid-state`:
     * what it is now; `replay-already-running`: the replay that is; anything else: "Replay failed to start").
     */
    const replay = async (id: string, call: ReplayCall) => {
      try {
        const request = await sendReplay(queryClient, id, call);
        void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.detail(id) });
        notify.success(toast.requestSent, {
          action: {
            label: toast.viewReplay,
            onClick: () => {
              openReplay(request.id);
            },
          },
        });
      } catch (error: unknown) {
        const status = isApiError(error) ? error.status : undefined;
        const described = describeError(error);
        if (status === 409 && described.slug === 'replay-already-running' && described.existingReplayId) {
          const existing = described.existingReplayId;
          notify.error(described.title, {
            action: {
              label: toast.viewReplay,
              onClick: () => {
                openReplay(existing);
              },
            },
          });
          refresh(queryClient, id);
        } else if (status === 409) {
          notify.error(described.title, described.description ? { description: described.description } : undefined);
          refresh(queryClient, id);
        } else {
          notify.error(toast.replayFailedToStart);
        }
      }
    };

    /** E-46, with the reason of the dialog. Rejects, so that the dialog shows why (DS-07). */
    const discard = async (id: string, reason: string) => {
      await sendDiscard(queryClient, id, reason);
      notify.success(toast.discarded);
      refresh(queryClient, id);
    };

    /** E-47. */
    const resolve = async (id: string, note: string) => {
      const rollback = setStatus(queryClient, id, 'RESOLVED');
      try {
        await write(api.POST('/api/v1/etl/dlq/{id}/resolve', { params: { path: { id } }, body: { note } }));
      } catch (error: unknown) {
        rollback();
        throw error;
      }
      notify.success(toast.resolved);
      refresh(queryClient, id);
    };

    /** E-43: the payload text as it is, not parsed (the API reads the body itself). Rejects with the Problem. */
    const edit = async (id: string, text: string) => {
      const record = await write(
        api.PUT('/api/v1/etl/dlq/{id}/payload', {
          params: { path: { id } },
          body: text,
          bodySerializer: (body: string) => body,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
      queryClient.setQueryData(keys.etl.dlq.detail(id), (cached: WithAsOf<DeadLetterDetail> | undefined) => ({
        asOf: cached?.asOf,
        data: record,
      }));
      patchDeadLetter(queryClient, id, (item) => ({ ...item, hasEditedPayload: true }));
      void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.actions() });
      notify.success(toast.saved);
      return record;
    };

    /** The same actions over many records, four at a time, each with its own key (§6, AC-4). */
    const bulk = async (
      kind: BulkKind,
      ids: readonly string[],
      options: { reason?: string; onProgress: (done: number) => void },
    ): Promise<BulkResult> => {
      const eligible = ids.filter((id) => {
        const status = cachedStatus(queryClient, id);
        return status === undefined || accepts(kind, status);
      });
      const outcome = await runBulk(
        eligible,
        async (id) => {
          if (kind === 'discard') await sendDiscard(queryClient, id, options.reason ?? '');
          else await sendReplay(queryClient, id, { kind, key: newIdempotencyKey() });
        },
        options.onProgress,
      );
      void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.summary() });
      void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.actions() });
      return { ...outcome, skipped: ids.length - eligible.length };
    };

    return { replay, discard, resolve, edit, bulk };
  }, [queryClient, openReplay]);
}
