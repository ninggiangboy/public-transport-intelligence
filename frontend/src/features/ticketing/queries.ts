import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import {
  CHART_LIMIT,
  PAGE_SIZE,
  periodKey,
  periodRange,
  type Anomaly,
  type ListFilters,
  type Period,
} from '@/features/ticketing/model';

// Queries of Ticketing anomalies (DOC-36 screens/ops-console-ticketing §5). Both lists sit under
// `['insights', 'ticketing', 'list']`, which `TICKETING_ANOMALY` alert events refetch (realtime/handlers.ts). A window
// is keyed by its name and resolved against `businessNow` at each fetch, so the refetch slides with the clock.

/** The safety net of §5 besides the alert events (also the app default, written out because §5 names it). */
const REFRESH_MS = 60_000;

interface Range {
  from: string;
  to: string;
}

function page(range: Range, filters: ListFilters, limit: number, cursor?: string) {
  return read(
    api.GET('/api/v1/insights/ticketing-anomalies', {
      params: {
        query: {
          ...range,
          category: filters.category,
          severity: filters.severity?.map(String),
          trigger: filters.trigger,
          salePointId: filters.salePointId,
          limit,
          cursor,
        },
      },
    }),
  );
}

/** The next page of a list: its cursor, and the range of the first page, which a cursor belongs to. */
interface NextPage {
  cursor: string;
  range: Range;
}

/**
 * E-15 by pages of 100 for the table. The first page resolves the range against `businessNow`; the pages after it
 * keep that range, so that their cursors stay valid while the clock moves on.
 */
export function listQuery(period: Period, filters: ListFilters, businessNow: () => number, enabled: boolean) {
  return infiniteQueryOptions({
    queryKey: keys.insights.ticketingList({ ...periodKey(period), ...filters }),
    queryFn: async ({ pageParam }) => {
      const range = pageParam?.range ?? periodRange(period, businessNow());
      return { ...(await page(range, filters, PAGE_SIZE, pageParam?.cursor)), range };
    },
    initialPageParam: undefined as NextPage | undefined,
    getNextPageParam: (last): NextPage | undefined =>
      last.data.nextCursor ? { cursor: last.data.nextCursor, range: last.range } : undefined,
    refetchInterval: REFRESH_MS,
    enabled,
  });
}

export interface ChartData {
  items: Anomaly[];
  /** The range the items were read for: the chart's hours. */
  range: { from: string; to: string };
  /** More than {@link CHART_LIMIT} anomalies in the range: only the newest are drawn. */
  truncated: boolean;
}

/** Up to 500 anomalies of the range for "Anomalies over time", five pages at most (§4). */
export function chartQuery(period: Period, filters: ListFilters, businessNow: () => number, enabled: boolean) {
  return queryOptions({
    queryKey: keys.insights.ticketingList({ ...periodKey(period), ...filters, chart: true }),
    queryFn: async (): Promise<ChartData> => {
      const range = periodRange(period, businessNow());
      const items: Anomaly[] = [];
      let cursor: string | undefined;
      do {
        const result = await page(range, filters, PAGE_SIZE, cursor);
        items.push(...result.data.items);
        cursor = result.data.nextCursor;
      } while (cursor && items.length < CHART_LIMIT);
      return {
        items: items.slice(0, CHART_LIMIT),
        range,
        truncated: cursor !== undefined || items.length > CHART_LIMIT,
      };
    },
    refetchInterval: REFRESH_MS,
    enabled,
  });
}

/** E-16; `alert.updated` of the same anomaly invalidates it (triage has just classified it). */
export function detailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.ticketingDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/ticketing-anomalies/{id}', { params: { path: { id } } })),
  });
}
