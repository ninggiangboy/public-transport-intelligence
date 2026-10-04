import { useMutation, useQueryClient } from '@tanstack/react-query';

import { api, write } from '@/api/client';
import { dropAlert, patchAlert } from '@/features/alerts/cache';
import { en } from '@/i18n/en';
import { actorOf } from '@/lib/format';
import { notify } from '@/lib/notify';

/** A row acknowledged under "Unacknowledged" fades, then leaves the list after this long (screens/alert-feed §6). */
export const LEAVE_AFTER_MS = 2_000;

/**
 * E-21, optimistic (UX ≤ 300 ms, J-2): the alert reads as acknowledged by `username` in every cached list and the
 * sidebar badge at once, without a toast (DOC-37 §2.7). A failure rolls back and says so; 404 drops the row.
 */
export function useAcknowledge(username: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => write(api.POST('/api/v1/alerts/{id}/ack', { params: { path: { id } } })),
    onMutate: (id) => {
      const rollback = patchAlert(queryClient, id, (alert) => ({
        ...alert,
        acknowledgedAt: new Date().toISOString(),
        acknowledgedBy: username ? actorOf(username) : '',
      }));
      return { rollback };
    },
    onError: (error, id, context) => {
      if ((error as { status?: unknown }).status === 404) {
        dropAlert(queryClient, id);
        return;
      }
      context?.rollback();
      notify.error(en.alerts.ackFailed);
    },
    onSuccess: (alert, id) => {
      // The server keeps the first person who acknowledged it (E-21 is idempotent).
      patchAlert(queryClient, id, () => alert);
      setTimeout(() => {
        dropAlert(queryClient, id, (queryKey) => {
          const filters = queryKey[2] as { state?: string } | undefined;
          return queryKey[1] === 'list' && filters?.state === 'unacknowledged';
        });
      }, LEAVE_AFTER_MS);
    },
  });
}
