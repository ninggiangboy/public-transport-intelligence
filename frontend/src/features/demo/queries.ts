import { queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { getCatalog, getRun, getRuns, getSimStatus } from '@/api/sim';

// Queries of Demo control (DOC-36 screens/demo-control §5). The simulator's times are real time, except `businessNow`.

const HOUR = 60 * 60_000;
/** `GET /sim/status` while the page shows (§5): refetchInterval stops in a hidden tab. */
const STATUS_REFRESH_MS = 2_000;
const RUNS_REFRESH_MS = 5_000;
/** One run, while it runs and its drawer is open. */
const RUN_REFRESH_MS = 2_000;
/** The platform's reactions to a running scenario (§4.1). */
const REACTION_REFRESH_MS = 5_000;
/** The history shows at most this many runs (§4). */
export const HISTORY_LIMIT = 50;

export function statusQuery() {
  return queryOptions({
    queryKey: keys.sim.status(),
    queryFn: getSimStatus,
    refetchInterval: STATUS_REFRESH_MS,
  });
}

/** The catalog does not change while the simulator runs: once per session (§5). */
export function catalogQuery() {
  return queryOptions({
    queryKey: keys.sim.scenarios(),
    queryFn: getCatalog,
    staleTime: Number.POSITIVE_INFINITY,
    refetchInterval: false,
  });
}

export function runsQuery() {
  return queryOptions({
    queryKey: keys.sim.runs(),
    queryFn: () => getRuns(HISTORY_LIMIT),
    refetchInterval: RUNS_REFRESH_MS,
  });
}

/** One run for the drawer: every 2 s while it runs, then no more. */
export function runQuery(runId: string) {
  return queryOptions({
    queryKey: keys.sim.run(runId),
    queryFn: () => getRun(runId),
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? RUN_REFRESH_MS : false),
  });
}

/** E-01, for the route of a run and the route field of the form. */
export function routesQuery() {
  return queryOptions({
    queryKey: keys.routes.list(),
    queryFn: () => read(api.GET('/api/v1/routes')),
    staleTime: HOUR,
    refetchInterval: false,
  });
}

/**
 * E-20: alerts created since the run started, on its route (§4.1). Under `alerts`, so alert events refresh it too.
 * E-20 has no sale point filter: a ticketing run reads every ticketing alert and keeps those of its sale point.
 */
export function reactionAlertsQuery(since: string, filter: { routeId?: string; type?: string }) {
  const query = {
    since,
    limit: 20,
    ...(filter.routeId ? { routeId: [filter.routeId] } : {}),
    ...(filter.type ? { type: [filter.type] } : {}),
  };
  return queryOptions({
    queryKey: keys.alerts.list(query),
    queryFn: () => read(api.GET('/api/v1/alerts', { params: { query } })),
    refetchInterval: REACTION_REFRESH_MS,
  });
}

/** E-17: the dispatch suggestion of a bunching episode, once it has one. */
export function suggestionQuery(bunchingId: string) {
  return queryOptions({
    queryKey: keys.insights.dispatch({ bunchingId }),
    queryFn: () =>
      read(api.GET('/api/v1/insights/dispatch-suggestions', { params: { query: { bunchingId, limit: 1 } } })),
    refetchInterval: (query) => ((query.state.data?.data.items.length ?? 0) > 0 ? false : REACTION_REFRESH_MS),
  });
}

/** E-41: open dead letters; "+{n} dead letters" is the change since the run was first seen (§4.1). */
export function deadLetterSummaryQuery() {
  return queryOptions({
    queryKey: keys.etl.dlq.summary(),
    queryFn: () => read(api.GET('/api/v1/etl/dlq/summary')),
    refetchInterval: REACTION_REFRESH_MS,
  });
}
