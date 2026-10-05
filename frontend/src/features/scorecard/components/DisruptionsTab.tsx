import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import type { components } from '@/api/generated/schema';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { Button } from '@/components/ui/button';
import { DisruptionDrawer } from '@/features/scorecard/components/DisruptionDrawer';
import { directionName } from '@/features/scorecard/display';
import { rangeInstants, type DayRange } from '@/features/scorecard/model';
import { disruptionListQuery } from '@/features/scorecard/queries';
import type { RouteScorecardSearch } from '@/features/scorecard/search';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { formatDelaySeconds, formatZScore } from '@/lib/format';
import { formatDateTime } from '@/lib/time';

type RouteDetail = components['schemas']['RouteDetailResponse'];
type Disruption = components['schemas']['DisruptionResponse'];

const copy = scorecardCopy.scorecard;
const columnsCopy = copy.disruptions.columns;

function useColumns(route: RouteDetail, timeZone: string) {
  return useMemo(() => {
    const col = columnHelper<Disruption>();
    const when = (at: string) => formatDateTime(at, { timeZone });
    return [
      col.accessor('episodeStart', {
        header: columnsCopy.started,
        cell: (info) => <span className="whitespace-nowrap tabular-nums">{when(info.getValue())}</span>,
      }),
      col.accessor('episodeEnd', {
        header: columnsCopy.ended,
        cell: (info) => {
          const end = info.getValue();
          return end ? (
            <span className="whitespace-nowrap tabular-nums">{when(end)}</span>
          ) : (
            <span className="font-medium text-tone-danger-fg">{copy.disruptions.ongoing}</span>
          );
        },
      }),
      col.accessor('directionId', {
        header: columnsCopy.direction,
        cell: (info) =>
          directionName(
            route.directions.find((d) => d.directionId === info.getValue()),
            info.getValue(),
          ),
      }),
      col.accessor('peakAvgDelaySeconds', {
        header: columnsCopy.peakDelay,
        cell: (info) => formatDelaySeconds(info.getValue()),
        meta: { align: 'right' },
      }),
      col.accessor('peakZScore', {
        header: columnsCopy.peakZ,
        cell: (info) => {
          const z = info.getValue();
          return z === undefined ? en.kv.empty : formatZScore(z);
        },
        meta: { align: 'right' },
      }),
      col.accessor('likelyCause', {
        header: columnsCopy.cause,
        cell: (info) => {
          const cause = info.getValue();
          return cause ? (alertsCopy.likelyCause[cause] ?? cause) : en.kv.empty;
        },
      }),
      col.display({
        id: 'outcome',
        header: columnsCopy.outcome,
        cell: (info) => {
          const episode = info.row.original;
          if (!episode.episodeEnd) return copy.disruptions.ongoing;
          return episode.closeReason
            ? (alertsCopy.closeReason[episode.closeReason] ?? episode.closeReason)
            : (alertsCopy.episodeStatus[episode.status] ?? episode.status);
        },
      }),
    ];
  }, [route, timeZone]);
}

/** Tab "Disruptions" (§4): E-12 of the route over the range, newest first, in keyset pages; a row opens E-13. */
export function DisruptionsTab({
  route,
  range,
  timezone,
  search,
  onSearch,
}: {
  route: RouteDetail;
  range: DayRange;
  timezone: string;
  search: RouteScorecardSearch;
  onSearch: (change: Partial<RouteScorecardSearch>, replace?: boolean) => void;
}) {
  const list = useInfiniteQuery(disruptionListQuery(route.routeId, rangeInstants(range, timezone)));
  const items = useMemo(() => list.data?.pages.flatMap((page) => page.data.items) ?? [], [list.data]);
  const columns = useColumns(route, timezone);

  return (
    <section
      aria-label={copy.tabs.disruptions}
      className="overflow-hidden rounded-lg border border-border bg-card shadow-sm"
    >
      {list.isError && !list.data ? (
        <ErrorState
          error={list.error}
          variant="block"
          panel={copy.panels.disruptions}
          onRetry={() => void list.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          data={items}
          getRowId={(episode) => episode.id}
          selectedId={search.disruption}
          onRowOpen={(episode) => {
            onSearch({ disruption: episode.id }, false);
          }}
          isLoading={!list.data}
          dimmed={list.isPlaceholderData}
          caption={copy.disruptions.caption(route.displayName)}
          empty={<EmptyState title={copy.disruptions.empty} />}
        />
      )}
      {list.hasNextPage ? (
        <div className="flex justify-center border-t border-border px-4 py-3">
          <Button
            variant="outline"
            size="sm"
            disabled={list.isFetchingNextPage}
            onClick={() => void list.fetchNextPage()}
          >
            {en.common.loadMore}
          </Button>
        </div>
      ) : null}
      {search.disruption ? (
        <DisruptionDrawer
          id={search.disruption}
          route={route}
          timezone={timezone}
          onClose={() => {
            onSearch({ disruption: undefined }, false);
          }}
        />
      ) : null}
    </section>
  );
}
