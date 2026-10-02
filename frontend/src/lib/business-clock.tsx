import { createContext, useContext, useMemo, type ReactNode } from 'react';

import { browserTimeZone } from '@/lib/time';

/**
 * The clock of the data (DOC-34 §8): `now()` is `businessNow` in epoch milliseconds, which can differ from the wall
 * clock by hours when the simulator runs on a shifted clock (DR-67), and `timezone` is the agency's IANA zone.
 */
export interface BusinessClock {
  now: () => number;
  timezone: string;
}

const wallClock: BusinessClock = { now: () => Date.now(), timezone: browserTimeZone() };

const BusinessClockContext = createContext<BusinessClock>(wallClock);

interface BusinessClockProviderProps {
  /** Defaults to the wall clock. P5-04 feeds it from `/system/freshness` and the SSE heartbeat. */
  now?: () => number;
  /** Defaults to the browser's zone, which is also the fallback before a feed is active (DOC-34 §8). */
  timezone?: string;
  children: ReactNode;
}

export function BusinessClockProvider({ now, timezone, children }: BusinessClockProviderProps) {
  const value = useMemo<BusinessClock>(
    () => ({ now: now ?? wallClock.now, timezone: timezone ?? wallClock.timezone }),
    [now, timezone],
  );
  return <BusinessClockContext value={value}>{children}</BusinessClockContext>;
}

export function useBusinessClock(): BusinessClock {
  return useContext(BusinessClockContext);
}
