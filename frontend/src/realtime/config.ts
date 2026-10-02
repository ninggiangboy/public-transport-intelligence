import type { Channel } from '@/realtime/types';

/** Constants of the client side of the event stream (DOC-26 §8, §11). */

/** Reconnect backoff: 1 s, doubling to 30 s, plus 0-1 s of jitter (§8.2). */
export const BACKOFF_INITIAL_MS = 1_000;
export const BACKOFF_MAX_MS = 30_000;
export const BACKOFF_JITTER_MS = 1_000;

/** No frame, not even a heartbeat, for this long: the connection is dead (§8.2). */
export const DEAD_AFTER_MS = 45_000;

/** Not open for this long: poll the REST endpoints instead (§8.3). */
export const POLLING_AFTER_MS = 5_000;

/** Poll period per channel while `status` is `polling` (§8.3). */
export const POLL_PERIOD_MS: Record<Channel, number> = {
  vehicles: 5_000,
  alerts: 30_000,
  jobs: 15_000,
  dlq: 30_000,
};

/** Registrations that change within this window open the connection once (§8.1). */
export const RESUBSCRIBE_DEBOUNCE_MS = 500;

/** A tab hidden for this long closes the connection (§8.2). */
export const HIDDEN_CLOSE_MS = 5 * 60_000;

/** The server refuses more route filters than this (DOC-26 §7). */
export const MAX_ROUTE_IDS = 20;

/** Cache invalidations that would otherwise run on every event are coalesced to one per window (DOC-26 §9). */
export const INVALIDATE_THROTTLE_MS = 2_000;

/** Vehicles older than `businessNow` by this much are hidden (DOC-33 §5.1). */
export const VEHICLE_STALE_MS = 5 * 60_000;

/** Ids remembered to drop frames the server sends twice (DOC-33 §2.2). */
export const DEDUPE_IDS = 1_000;

/** `lastEventAt` is published at most this often, so that a busy stream does not re-render every subscriber. */
export const LAST_EVENT_PUBLISH_MS = 1_000;

/** Channels that need a signed-in viewer (DOC-33 §4). */
export const RESTRICTED_CHANNELS: readonly Channel[] = ['jobs', 'dlq'];

/** Canonical order, so that the same set always gives the same URL. */
export const CHANNEL_ORDER: readonly Channel[] = ['vehicles', 'alerts', 'jobs', 'dlq'];

export const STREAM_PATH = '/api/v1/stream';
