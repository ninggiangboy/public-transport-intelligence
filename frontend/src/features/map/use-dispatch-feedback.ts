import { useMutation, useQueryClient } from '@tanstack/react-query';

import { api, write, type WithAsOf } from '@/api/client';
import type { components } from '@/api/generated/schema';
import { keys } from '@/api/keys';
import { useAccess } from '@/app/access';
import { mapCopy } from '@/i18n/map';
import { actorOf } from '@/lib/format';
import { notify } from '@/lib/notify';

type Episode = components['schemas']['BunchingDetailResponse'];
type Feedback = 'accepted' | 'ignored';

/**
 * Accept / Dismiss of a dispatch suggestion (E-18, UC-04): the episode in the cache takes the choice at once, so the
 * button changes in well under 300 ms (AC-5), and goes back if the request fails (UC-04 E1). The other button
 * overrides it later (UC-04 3b).
 */
export function useDispatchFeedback(episodeId: string) {
  const queryClient = useQueryClient();
  const { me } = useAccess();
  const queryKey = keys.insights.bunchingDetail(episodeId);
  return useMutation({
    mutationFn: ({ suggestionId, feedback }: { suggestionId: string; feedback: Feedback }) =>
      write(
        api.POST('/api/v1/insights/dispatch-suggestions/{id}/feedback', {
          params: { path: { id: suggestionId } },
          body: { feedback },
        }),
      ),
    onMutate: async ({ feedback }) => {
      await queryClient.cancelQueries({ queryKey });
      const previous = queryClient.getQueryData<WithAsOf<Episode>>(queryKey);
      if (previous?.data.suggestion) {
        queryClient.setQueryData<WithAsOf<Episode>>(queryKey, {
          ...previous,
          data: {
            ...previous.data,
            suggestion: {
              ...previous.data.suggestion,
              operatorFeedback: feedback,
              feedbackBy: me?.username ? actorOf(me.username) : previous.data.suggestion.feedbackBy,
              feedbackAt: new Date().toISOString(),
            },
          },
        });
      }
      return { previous };
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(queryKey, context.previous);
      notify.error(mapCopy.map.toast.feedbackFailed);
    },
    onSuccess: (response) => {
      queryClient.setQueryData<WithAsOf<Episode>>(queryKey, (cached) =>
        cached ? { ...cached, data: { ...cached.data, suggestion: response } } : cached,
      );
      notify.success(mapCopy.map.toast.feedbackSaved);
    },
  });
}
