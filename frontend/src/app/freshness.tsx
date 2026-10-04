import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

import { api, read } from '@/api/client';
import type { components } from '@/api/generated/schema';
import { keys } from '@/api/keys';
import { useSession } from '@/app/session';
import { BusinessClockProvider } from '@/lib/business-clock';
import { toMillis } from '@/lib/time';
import { useRealtimeControls, useRealtimeState } from '@/realtime/useRealtime';

export type FreshnessResponse = components['schemas']['FreshnessResponse'];

/** E-60 is polled this often (DOC-34 §9.2 item 4). */
export const FRESHNESS_POLL_MS = 15_000;

export interface Freshness {
  /** The last good answer; kept when a later poll fails. */
  data?: FreshnessResponse;
  /** The last poll failed (network error, 503). */
  failed: boolean;
  /** Wall-clock time of the last good answer. */
  updatedAt?: number;
}

const FreshnessContext = createContext<Freshness>({ failed: false });

/** Item 4 of DOC-34 §9.2: data freshness, `businessNow` and the agency's time zone for the whole app. */
export function FreshnessProvider({ children }: { children: ReactNode }) {
  const query = useQuery({
    queryKey: keys.system.freshness(),
    queryFn: async () => (await read(api.GET('/api/v1/system/freshness'))).data,
    refetchInterval: FRESHNESS_POLL_MS,
  });
  const value: Freshness = {
    data: query.data,
    failed: query.isError,
    updatedAt: query.dataUpdatedAt || undefined,
  };
  return <FreshnessContext value={value}>{children}</FreshnessContext>;
}

export function useFreshness(): Freshness {
  return useContext(FreshnessContext);
}

interface Anchor {
  business: number;
  /** `performance.now()` when `business` was read: the clock runs on from there (DOC-34 §8). */
  at: number;
}

/**
 * The business clock of DOC-34 §8, fed by E-60 and the SSE heartbeat, whichever arrived last. It sits inside the
 * `RealtimeProvider` to see heartbeats. Between two readings the clock runs on `performance.now()`.
 */
export function LiveBusinessClock({ children }: { children: ReactNode }) {
  const { data } = useFreshness();
  const { businessNow: heartbeat } = useRealtimeState();
  const anchor = useRef<Anchor | undefined>(undefined);
  const polled = data?.businessNow;

  useEffect(() => {
    if (polled) anchor.current = { business: toMillis(polled), at: performance.now() };
  }, [polled]);
  useEffect(() => {
    if (heartbeat) anchor.current = { business: toMillis(heartbeat), at: performance.now() };
  }, [heartbeat]);

  // One function for the app's lifetime, so that time-based hooks do not restart their timers on every reading.
  const [now] = useState(() => () => {
    const current = anchor.current;
    return current ? current.business + performance.now() - current.at : Date.now();
  });
  return (
    <BusinessClockProvider now={now} timezone={data?.activeFeed?.timezone}>
      {children}
    </BusinessClockProvider>
  );
}

/** Reopens the event stream with the new token after sign-in, renewal or sign-out (DOC-26 §8.2). */
export function SessionStreamSync() {
  const { accessToken } = useSession();
  const { reconnect } = useRealtimeControls();
  const previous = useRef(accessToken);
  useEffect(() => {
    if (previous.current === accessToken) return;
    previous.current = accessToken;
    reconnect();
  }, [accessToken, reconnect]);
  return null;
}
