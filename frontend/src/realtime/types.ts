/** Public types of `useRealtime` (DOC-26 §8.1). */

export type Channel = 'vehicles' | 'alerts' | 'jobs' | 'dlq';

export interface RealtimeOptions {
  channels: Channel[];
  /** At most 20; empty or missing means every route. */
  routeIds?: string[];
}

export type RealtimeStatus = 'connecting' | 'open' | 'reconnecting' | 'polling';

export interface RealtimeState {
  status: RealtimeStatus;
  /** Wall clock of the last frame of any type (published at most once a second). */
  lastEventAt?: Date;
  /** From the last heartbeat. */
  businessNow?: string;
}
