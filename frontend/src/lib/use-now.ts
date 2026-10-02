import { useEffect, useState } from 'react';

import { useBusinessClock } from '@/lib/business-clock';
import { formatRelative, relativeRefreshMs, type Instant } from '@/lib/time';

/** Which clock a time is measured on (DOC-34 §8): event time follows `businessNow`, audit time the machine clock. */
export type TimeAxis = 'event' | 'audit';

/**
 * The current time on the axis, refreshed every second while `at` is under a minute away and every 30 s after
 * (DOC-35 §5.2). `wakeAt` (epoch ms on the same axis) adds a refresh at that moment, for a caller that must notice
 * a deadline, such as the end of freshness, without waiting for the next tick.
 */
export function useAxisNow(at: Instant | undefined, axis: TimeAxis, wakeAt?: number): number {
  const clock = useBusinessClock();
  const read = axis === 'event' ? clock.now : Date.now;
  const [now, setNow] = useState(read);

  useEffect(() => {
    if (at === undefined) return;
    const anchor: Instant = at;
    let timer: ReturnType<typeof setTimeout>;
    const schedule = (delay: number) => {
      timer = setTimeout(tick, delay);
    };
    function tick() {
      const current = read();
      setNow(current);
      const regular = relativeRefreshMs(anchor, current);
      const untilWake = wakeAt === undefined ? Number.POSITIVE_INFINITY : wakeAt - current;
      schedule(untilWake > 0 ? Math.min(regular, untilWake) : regular);
    }
    // The first tick is asynchronous: it catches `now` up when `at` changes between two ticks.
    schedule(0);
    return () => {
      clearTimeout(timer);
    };
  }, [at, read, wakeAt]);

  return now;
}

/** "5 s ago" for an instant on the axis, kept fresh as time passes (DOC-37 §4.3). */
export function useRelative(at: Instant, axis: TimeAxis): string {
  const clock = useBusinessClock();
  const now = useAxisNow(at, axis);
  return formatRelative(at, now, clock.timezone);
}
