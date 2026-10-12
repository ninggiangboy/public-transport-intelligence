import { useSyncExternalStore } from 'react';

// "Recent requests" of the Run a job card (§4): the jobs started from this tab, newest first, at most five. A module
// store rather than component state, so the list is still there after a visit to Pipeline.

export interface RecentRequest {
  id: string;
  jobName: string;
  /** Wall clock when it was sent. */
  sentAt: number;
  /** The end of the request was announced (toast, feed refresh), so a remount does not do it twice. */
  settled?: boolean;
}

const LIMIT = 5;
let items: readonly RecentRequest[] = [];
const listeners = new Set<() => void>();

function emit(next: readonly RecentRequest[]) {
  items = next;
  for (const listener of listeners) listener();
}

export const recentRequests = {
  add(request: RecentRequest) {
    emit([request, ...items.filter((item) => item.id !== request.id)].slice(0, LIMIT));
  },
  settle(id: string) {
    emit(items.map((item) => (item.id === id ? { ...item, settled: true } : item)));
  },
  snapshot: () => items,
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  /** Tests start from an empty list. */
  clear() {
    emit([]);
  },
};

export function useRecentRequests(): readonly RecentRequest[] {
  return useSyncExternalStore(recentRequests.subscribe, recentRequests.snapshot);
}
