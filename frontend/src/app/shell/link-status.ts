import type { Tone } from '@/components/tone';
import { POLL_PERIOD_MS } from '@/realtime/config';
import type { RealtimeState } from '@/realtime/useRealtime';

export type LinkStatus = 'live' | 'reconnecting' | 'polling' | 'offline';

/** The four states of DOC-37 §2.4. `connecting` (start-up) shows as live until the stream says otherwise. */
export function linkStatus(state: RealtimeState, online: boolean): LinkStatus {
  if (!online) return 'offline';
  if (state.status === 'polling') return 'polling';
  if (state.status === 'reconnecting') return 'reconnecting';
  return 'live';
}

export const LINK_TONE: Record<LinkStatus, Tone> = {
  live: 'success',
  reconnecting: 'warning',
  polling: 'neutral',
  offline: 'neutral',
};

/** "every {n} s" while polling. */
export function pollSeconds(state: RealtimeState): number {
  return Math.round((state.pollPeriodMs ?? POLL_PERIOD_MS.alerts) / 1000);
}
