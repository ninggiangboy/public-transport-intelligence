/**
 * The one connection to `GET /api/v1/stream` (DOC-26 §8), without React so that it can be tested with a fake server.
 * {@link RealtimeProvider} owns an instance; `useRealtime` registers channels on it.
 *
 * What it does, in the order of the spec:
 * - opens nothing while no component subscribes; merges the channels and routes of all subscribers and reopens when
 *   the merge changes, after 500 ms of quiet (§8.1);
 * - reconnects after any loss with 1 s, 2 s, 4 s ... 30 s backoff plus 0-1 s of jitter, and gives up on a connection
 *   that is silent for 45 s (§8.2);
 * - reacts to refusals: 401 renews the token and retries, then falls back to the public channels; 429 and 503 wait
 *   `Retry-After`; other 4xx are not retried with the same parameters (§8.2);
 * - polls the REST endpoints when it has not been open for 5 s (§8.3);
 * - closes a tab hidden for 5 minutes and reopens it, refetching what is on screen, when it shows again (§8.2).
 */
import { fetchEventSource, type EventSourceMessage } from '@microsoft/fetch-event-source';
import type { QueryClient } from '@tanstack/react-query';

import { getAuth } from '@/api/client';
import { normalizeList } from '@/api/keys';
import {
  BACKOFF_INITIAL_MS,
  BACKOFF_JITTER_MS,
  BACKOFF_MAX_MS,
  CHANNEL_ORDER,
  DEAD_AFTER_MS,
  DEDUPE_IDS,
  HIDDEN_CLOSE_MS,
  LAST_EVENT_PUBLISH_MS,
  MAX_ROUTE_IDS,
  POLL_PERIOD_MS,
  POLLING_AFTER_MS,
  RESTRICTED_CHANNELS,
  RESUBSCRIBE_DEBOUNCE_MS,
  STREAM_PATH,
} from '@/realtime/config';
import { createHandlers, type Handlers } from '@/realtime/handlers';
import { Poller, pollVehicles } from '@/realtime/polling';
import { parseFrame } from '@/realtime/schemas';
import type { Channel, RealtimeOptions, RealtimeState } from '@/realtime/types';

const MAX_RETRY_AFTER_MS = 5 * 60_000;

/** The server answered the request with an error status. */
class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly retryAfterMs?: number,
  ) {
    super(`The event stream answered ${String(status)}`);
  }
}

/** The server closed a stream that had been open. */
class StreamEnded extends Error {}

/** Nothing arrived for 45 s. */
class StreamSilent extends Error {}

interface Subscription {
  channels: Channel[];
  /** Empty means every route. */
  routeIds: string[];
}

/** One run of the connect loop for one merged subscription. A new subscription starts a new session. */
interface Session {
  requested: Subscription;
  aborted: boolean;
  /** Consecutive failures since the last successful open; the exponent of the backoff. */
  failures: number;
  /** After a 401 or 403 the restricted channels are dropped until the subscription changes. */
  publicOnly: boolean;
  renewTried: boolean;
  sentToken: boolean;
  /** The vehicles channel has no replay: after a loss the snapshot is fetched again on the next open. */
  needsSnapshot: boolean;
  attempt?: AbortController;
  sleepTimer?: ReturnType<typeof setTimeout>;
  wake?: () => void;
}

export interface ControllerOptions {
  queryClient: QueryClient;
  /** Defaults to the global `fetch`, looked up on every request so that a fetch installed later (MSW) is used. */
  fetch?: typeof globalThis.fetch;
}

/** `session.aborted` as the type checker cannot narrow it: it changes while the loop awaits. */
function gone(session: Session): boolean {
  return session.aborted;
}

function retryAfterMs(header: string | null): number | undefined {
  if (!header) return undefined;
  const seconds = Number(header);
  const ms = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(header) - Date.now();
  return Number.isNaN(ms) ? undefined : Math.min(Math.max(ms, 0), MAX_RETRY_AFTER_MS);
}

function sameSubscription(a: Subscription, b: Subscription): boolean {
  return a.channels.join() === b.channels.join() && a.routeIds.join() === b.routeIds.join();
}

export class RealtimeController {
  private readonly queryClient: QueryClient;
  private readonly fetchImpl: typeof globalThis.fetch | undefined;
  private readonly handlers: Handlers;
  private readonly poller: Poller;

  private readonly registrations = new Set<Subscription>();
  private readonly listeners = new Set<() => void>();
  private state: RealtimeState = { status: 'connecting' };

  private started = false;
  private session: Session | undefined;
  private debounceTimer: ReturnType<typeof setTimeout> | undefined;
  private pollTimer: ReturnType<typeof setTimeout> | undefined;
  private hiddenTimer: ReturnType<typeof setTimeout> | undefined;
  private closedWhileHidden = false;

  /** Kept in memory only (DOC-26 §8.2). */
  private lastEventId: string | undefined;
  private readonly seen = new Set<string>();
  private warned = false;

  constructor(options: ControllerOptions) {
    this.queryClient = options.queryClient;
    this.fetchImpl = options.fetch;
    this.handlers = createHandlers(options.queryClient);
    this.poller = new Poller(options.queryClient);
  }

  // -------------------------------------------------------------------------------------------------------------------
  // For React

  getState = (): RealtimeState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** Adds a subscriber's channels and routes to the merge; the returned function takes them out again. */
  register(options: RealtimeOptions): () => void {
    const registration: Subscription = {
      channels: CHANNEL_ORDER.filter((channel) => options.channels.includes(channel)),
      routeIds: normalizeList(options.routeIds),
    };
    this.registrations.add(registration);
    this.scheduleApply();
    return () => {
      this.registrations.delete(registration);
      this.scheduleApply();
    };
  }

  /** Starts listening to the page visibility and honours the registrations made so far. Returns the stop function. */
  start(): () => void {
    this.started = true;
    document.addEventListener('visibilitychange', this.onVisibility);
    if (document.visibilityState === 'hidden') this.armHiddenTimer();
    this.scheduleApply();
    return () => {
      this.stop();
    };
  }

  /** Reconnects now, with fresh credentials: call it when the token was refreshed (`userLoaded`, DOC-26 §8.2). */
  reconnect() {
    if (!this.started || this.closedWhileHidden) return;
    const want = this.merged();
    if (want.channels.length > 0) this.openSession(want);
  }

  private stop() {
    this.started = false;
    document.removeEventListener('visibilitychange', this.onVisibility);
    clearTimeout(this.debounceTimer);
    clearTimeout(this.hiddenTimer);
    this.closedWhileHidden = false;
    this.stopSession();
    this.goIdle();
    this.handlers.dispose();
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Subscription

  /** Union of the channels; the route filter is the union of the filters unless someone wants every route. */
  private merged(): Subscription {
    const channels = new Set<Channel>();
    const routes = new Set<string>();
    let everyRoute = false;
    for (const registration of this.registrations) {
      for (const channel of registration.channels) channels.add(channel);
      if (registration.channels.length === 0) continue;
      if (registration.routeIds.length === 0) everyRoute = true;
      for (const routeId of registration.routeIds) routes.add(routeId);
    }
    // More routes than the server accepts: ask for all of them; screens only read the keys they query.
    const routeIds = everyRoute || routes.size > MAX_ROUTE_IDS ? [] : normalizeList([...routes]);
    return { channels: CHANNEL_ORDER.filter((channel) => channels.has(channel)), routeIds };
  }

  private scheduleApply() {
    clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = undefined;
      this.applyRegistrations();
    }, RESUBSCRIBE_DEBOUNCE_MS);
  }

  private applyRegistrations() {
    if (!this.started || this.closedWhileHidden) return;
    const want = this.merged();
    if (want.channels.length === 0) {
      this.stopSession();
      this.goIdle();
    } else if (!this.session || !sameSubscription(this.session.requested, want)) {
      this.openSession(want);
    }
  }

  private openSession(requested: Subscription, needsSnapshot = false) {
    this.stopSession();
    const session: Session = {
      requested,
      aborted: false,
      failures: 0,
      publicOnly: false,
      renewTried: false,
      sentToken: false,
      needsSnapshot,
    };
    this.session = session;
    this.armPollTimer();
    void this.run(session);
  }

  private stopSession() {
    const session = this.session;
    if (!session) return;
    session.aborted = true;
    session.attempt?.abort();
    clearTimeout(session.sleepTimer);
    session.wake?.();
    this.session = undefined;
  }

  /** Nobody subscribes: no connection, no polling. */
  private goIdle() {
    clearTimeout(this.pollTimer);
    this.pollTimer = undefined;
    this.poller.stop();
    this.publish({ status: 'connecting' });
  }

  private effectiveChannels(session: Session): Channel[] {
    const { channels } = session.requested;
    return session.publicOnly ? channels.filter((channel) => !RESTRICTED_CHANNELS.includes(channel)) : channels;
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Connect loop

  private async run(session: Session) {
    while (!gone(session)) {
      const channels = this.effectiveChannels(session);
      if (channels.length === 0) return;
      this.setStatus(session.failures === 0 && !session.needsSnapshot ? 'connecting' : 'reconnecting');

      let delay: number;
      try {
        await this.openOnce(session, channels);
        if (gone(session)) return;
        throw new StreamEnded();
      } catch (error) {
        if (gone(session)) return;
        session.needsSnapshot = true;
        const next = await this.afterFailure(session, error);
        if (next === 'stop' || gone(session)) return;
        delay = next;
        // Down from now on, not from the next attempt: this starts the 5 s that lead to polling.
        this.setStatus('reconnecting');
      }
      if (delay > 0) await this.sleep(session, delay);
    }
  }

  /** Decides what a failure means: the delay before the next attempt, or `stop` when retrying cannot help. */
  private async afterFailure(session: Session, error: unknown): Promise<number | 'stop'> {
    if (!(error instanceof HttpError)) return this.backoff(session);
    if (error.status === 401) return this.afterUnauthorized(session);
    if (error.status === 403) return this.dropRestricted(session) ? 0 : 'stop';
    if (error.status === 429 || error.status >= 500) return error.retryAfterMs ?? this.backoff(session);
    // Any other 4xx: the same request would fail the same way (§8.2). The polling fallback takes over.
    return 'stop';
  }

  /** 401 (§8.2): renew once and retry; if that fails, keep going with what an anonymous user may open. */
  private async afterUnauthorized(session: Session): Promise<number | 'stop'> {
    const auth = getAuth();
    if (session.sentToken && auth && !session.renewTried) {
      session.renewTried = true;
      const renewed = await auth.renew().catch(() => false);
      if (renewed) return 0;
    }
    if (session.sentToken) auth?.expired();
    const dropped = this.dropRestricted(session);
    // `expired()` makes the user anonymous, which a public channel accepts.
    return dropped || (session.sentToken && !getAuth()?.accessToken()) ? 0 : 'stop';
  }

  /** Stops asking for `jobs` and `dlq`. Returns whether that changed anything. */
  private dropRestricted(session: Session): boolean {
    if (session.publicOnly || !session.requested.channels.some((channel) => RESTRICTED_CHANNELS.includes(channel))) {
      return false;
    }
    session.publicOnly = true;
    if (this.state.status === 'polling') this.poller.start(this.pollTarget(session));
    return true;
  }

  private backoff(session: Session): number {
    const base = Math.min(BACKOFF_INITIAL_MS * 2 ** session.failures, BACKOFF_MAX_MS);
    session.failures += 1;
    return base + Math.random() * BACKOFF_JITTER_MS;
  }

  private sleep(session: Session, ms: number): Promise<void> {
    return new Promise((resolve) => {
      session.wake = resolve;
      session.sleepTimer = setTimeout(resolve, ms);
    });
  }

  /** One connection, from the request to its end. Resolves only if the session was aborted; otherwise it throws. */
  private async openOnce(session: Session, channels: Channel[]) {
    const attempt = new AbortController();
    session.attempt = attempt;

    const headers: Record<string, string> = {};
    const token = getAuth()?.accessToken();
    session.sentToken = token !== undefined && token !== '';
    if (session.sentToken) headers.Authorization = `Bearer ${String(token)}`;
    if (this.lastEventId) headers['Last-Event-ID'] = this.lastEventId;

    const dead = { silent: false };
    let watchdog: ReturnType<typeof setTimeout> | undefined;
    const arm = () => {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => {
        dead.silent = true;
        attempt.abort();
      }, DEAD_AFTER_MS);
    };

    try {
      await fetchEventSource(this.url(channels, session.requested.routeIds), {
        signal: attempt.signal,
        headers,
        // Our own rules for hidden tabs (§8.2), not the library's.
        openWhenHidden: true,
        // Looked up on every request, so that a fetch installed after import (MSW in tests) is used.
        fetch: (input, init) => (this.fetchImpl ?? globalThis.fetch)(input, init),
        onopen: (response) => {
          if (!response.ok || !response.headers.get('Content-Type')?.includes('text/event-stream')) {
            return Promise.reject(
              new HttpError(response.ok ? 502 : response.status, retryAfterMs(response.headers.get('Retry-After'))),
            );
          }
          arm();
          this.onOpen(session);
          return Promise.resolve();
        },
        onmessage: (message) => {
          arm();
          this.onFrame(message);
        },
        onclose: () => {
          throw new StreamEnded();
        },
        // The retry policy is ours; rethrowing stops the library's own.
        onerror: (error: unknown) => {
          throw error instanceof Error ? error : new Error('The event stream failed');
        },
      });
    } finally {
      clearTimeout(watchdog);
    }
    if (dead.silent) throw new StreamSilent();
  }

  private url(channels: Channel[], routeIds: string[]): string {
    const url = new URL(STREAM_PATH, globalThis.location.origin);
    url.searchParams.set('channels', channels.join(','));
    for (const routeId of routeIds) url.searchParams.append('routeId', routeId);
    return url.toString();
  }

  private onOpen(session: Session) {
    session.failures = 0;
    session.renewTried = false;
    const catchUp = session.needsSnapshot || this.state.status === 'polling';
    session.needsSnapshot = false;
    this.setStatus('open');
    // The vehicles channel has no replay (DOC-26 §8.3): one snapshot closes the gap. The other channels replay or resync.
    if (catchUp && this.effectiveChannels(session).includes('vehicles')) {
      pollVehicles(this.queryClient, this.vehicleRoutes()).catch(() => undefined);
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Frames

  private onFrame(message: EventSourceMessage) {
    const now = new Date();
    const last = this.state.lastEventAt;
    if (!last || now.getTime() - last.getTime() >= LAST_EVENT_PUBLISH_MS) this.publish({ lastEventAt: now });
    // Frames the client cannot use still move the position: the server has sent them.
    if (message.id) this.lastEventId = message.id;

    try {
      const frame = parseFrame(message.event, message.data);
      if (frame.kind === 'unknown') return;
      if (frame.kind === 'invalid') {
        if (!this.warned) {
          this.warned = true;
          console.warn(`Ignoring an event stream frame of type "${frame.type}": ${frame.reason}`);
        }
        return;
      }
      const { event } = frame;
      if ('id' in event && event.id) {
        if (this.seen.has(event.id)) return;
        this.seen.add(event.id);
        const oldest = this.seen.values().next();
        if (this.seen.size > DEDUPE_IDS && !oldest.done) this.seen.delete(oldest.value);
      }
      if (event.type === 'heartbeat') this.publish({ businessNow: event.data.businessNow });
      this.handlers.apply(event);
    } catch (error) {
      // A bad frame must never end the stream.
      if (!this.warned) {
        this.warned = true;
        console.warn('Ignoring an event stream frame that could not be applied', error);
      }
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Status and polling

  private setStatus(next: RealtimeState['status']) {
    const current = this.state.status;
    if (current === next) return;
    // Polling ends only when the stream opens.
    if (current === 'polling' && next !== 'open') return;
    if (next === 'open') {
      clearTimeout(this.pollTimer);
      this.pollTimer = undefined;
      this.poller.stop();
    }
    this.publish({ status: next });
    if (next !== 'open') this.armPollTimer();
  }

  private armPollTimer() {
    if (this.pollTimer || this.closedWhileHidden || this.state.status === 'open' || this.state.status === 'polling') {
      return;
    }
    this.pollTimer = setTimeout(() => {
      this.pollTimer = undefined;
      const session = this.session;
      if (!session || this.state.status === 'open') return;
      const target = this.pollTarget(session);
      this.publish({
        status: 'polling',
        pollPeriodMs: Math.min(...target.channels.map((channel) => POLL_PERIOD_MS[channel])),
      });
      this.poller.start(target);
    }, POLLING_AFTER_MS);
  }

  private pollTarget(session: Session) {
    return { channels: this.effectiveChannels(session), vehicleRoutes: this.vehicleRoutes() };
  }

  /** The distinct route filters of the subscribers to `vehicles`. */
  private vehicleRoutes(): string[][] {
    const filters = new Map<string, string[]>();
    for (const registration of this.registrations) {
      if (registration.channels.includes('vehicles')) filters.set(registration.routeIds.join(), registration.routeIds);
    }
    return [...filters.values()];
  }

  private publish(change: Partial<RealtimeState>) {
    this.state = { ...this.state, ...change };
    for (const listener of this.listeners) listener();
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Page visibility

  private readonly onVisibility = () => {
    if (document.visibilityState === 'hidden') {
      this.armHiddenTimer();
      return;
    }
    clearTimeout(this.hiddenTimer);
    this.hiddenTimer = undefined;
    if (!this.closedWhileHidden) return;
    this.closedWhileHidden = false;
    // What was on screen is stale; the stream will probably start with a `resync`.
    void this.queryClient.invalidateQueries();
    const want = this.merged();
    if (this.started && want.channels.length > 0) this.openSession(want, true);
  };

  private armHiddenTimer() {
    clearTimeout(this.hiddenTimer);
    this.hiddenTimer = setTimeout(() => {
      this.hiddenTimer = undefined;
      this.closedWhileHidden = true;
      this.stopSession();
      clearTimeout(this.pollTimer);
      this.pollTimer = undefined;
      this.poller.stop();
      this.publish({ status: 'reconnecting' });
    }, HIDDEN_CLOSE_MS);
  }
}
