import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useState } from 'react';

import type { WithAsOf } from '@/api/client';
import type { components } from '@/api/generated/schema';
import { keys } from '@/api/keys';
import { putFlag } from '@/features/controls/queries';
import { notify } from '@/lib/notify';
import { describeError } from '@/lib/problem-copy';

type Flags = WithAsOf<components['schemas']['ItemsResponseRuntimeFlagResponse']>;

/** A failed row flashes `danger` this long (DOC-37 §2.7). */
const FLASH_MS = 2_000;

export interface FlagToggle {
  /** Sets the flag at once in the cache, sends E-57, and on failure puts it back with an error toast (§6). */
  set: (key: string, value: boolean, success: string) => void;
  /** The flag whose change just failed, for the row's flash. */
  failed: string | undefined;
}

export function useFlagToggle(): FlagToggle {
  const queryClient = useQueryClient();
  const [failed, setFailed] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (failed === undefined) return;
    const timer = setTimeout(() => {
      setFailed(undefined);
    }, FLASH_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [failed]);

  const set = useCallback(
    (key: string, value: boolean, success: string) => {
      const queryKey = keys.etl.flags();
      void (async () => {
        await queryClient.cancelQueries({ queryKey });
        const previous = queryClient.getQueryData<Flags>(queryKey);
        if (previous) {
          queryClient.setQueryData<Flags>(queryKey, {
            ...previous,
            data: {
              items: previous.data.items.map((flag) => (flag.key === key ? { ...flag, value } : flag)),
            },
          });
        }
        try {
          const saved = await putFlag(key, value);
          queryClient.setQueryData<Flags>(queryKey, (current) =>
            current
              ? { ...current, data: { items: current.data.items.map((flag) => (flag.key === key ? saved : flag)) } }
              : current,
          );
          notify.success(success);
        } catch (error: unknown) {
          if (previous) queryClient.setQueryData(queryKey, previous);
          setFailed(key);
          const described = describeError(error);
          notify.error(described.title, described.description ? { description: described.description } : undefined);
        } finally {
          void queryClient.invalidateQueries({ queryKey });
        }
      })();
    },
    [queryClient],
  );

  return { set, failed };
}
