import { useEffect, useState } from 'react';

/** The wall clock, refreshed every second while `enabled`: the duration of a run that is still going (§4). */
export function useTicker(enabled: boolean): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!enabled) return;
    const timer = setInterval(() => {
      setNow(Date.now());
    }, 1_000);
    return () => {
      clearInterval(timer);
    };
  }, [enabled]);
  return now;
}
