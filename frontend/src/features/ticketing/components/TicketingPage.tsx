import { queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { SearchX } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { useFreshness } from '@/app/freshness';
import { AppLink } from '@/components/AppLink';
import { Callout } from '@/components/Callout';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { NewItemsPill } from '@/components/NewItemsPill';
import { PageHeader } from '@/components/PageHeader';
import { SegmentedControl } from '@/components/SegmentedControl';
import { SplitView } from '@/components/SplitView';
import { Button } from '@/components/ui/button';
import { AnomalyChart } from '@/features/ticketing/components/AnomalyChart';
import { AnomalyDetail } from '@/features/ticketing/components/AnomalyDetail';
import { AnomalyTable, type RouteLook } from '@/features/ticketing/components/AnomalyTable';
import { TicketingFilters } from '@/features/ticketing/components/TicketingFilters';
import { CLEARED, isFiltered, listFilters, newHeadIds, resolvePeriod, type Anomaly } from '@/features/ticketing/model';
import { chartQuery, listQuery } from '@/features/ticketing/queries';
import { ticketingSearch, WINDOWS, type TicketingSearch, type Window } from '@/features/ticketing/search';
import { en } from '@/i18n/en';
import { ticketingCopy } from '@/i18n/ticketing';
import { useDocumentTitle } from '@/lib/browser';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCount, formatDuration } from '@/lib/format';
import { formatDateTime, formatTime } from '@/lib/time';
import { useRealtime } from '@/realtime/useRealtime';

const copy = ticketingCopy;
/** Below this scroll offset new anomalies slide in at the top; further down they wait behind the pill (§6). */
const PILL_AFTER_PX = 120;
/** A new row keeps its tint this long (§6). */
const HIGHLIGHT_MS = 3_000;
const DAY_MS = 86_400_000;
/** The page of Pipeline that shows the ticketing listener (§4 "Check source"). */
const CHECK_SOURCE = '/ops/jobs?kind=STREAM';

/** E-01 for the route badges, cached for the session like every other screen. */
const routesQuery = queryOptions({
  queryKey: keys.routes.list(),
  queryFn: () => read(api.GET('/api/v1/routes')),
  staleTime: 3_600_000,
  refetchInterval: false,
});

/** /ops/ticketing: unusual sales or refunds per sale point, read-only (DOC-36 screens/ops-console-ticketing). */
export function TicketingPage({ raw }: { raw: Record<string, unknown> }) {
  useDocumentTitle(copy.title);
  const navigate = useNavigate({ from: '/ops/ticketing' });
  const clock = useBusinessClock();
  const freshness = useFreshness();
  useRealtime({ channels: ['alerts'] });

  // Bad values are dropped here, not in the route, so that the schema is not part of the first paint (DR-110).
  const search = useMemo(() => ticketingSearch.parse(raw), [raw]);
  const period = resolvePeriod(search);
  const filters = useMemo(() => listFilters(search), [search]);
  const filtered = isFiltered(search);
  // A window ends at businessNow (§2). The shared clock takes E-60 in an effect of the shell, after this screen's
  // queries have started, so the range is computed from the last E-60 answer here; the lists wait for that first
  // answer (or its failure, then the machine clock).
  const polled = freshness.data?.businessNow;
  const polledAt = freshness.updatedAt;
  const businessNow = useCallback(
    () => (polled && polledAt ? Date.parse(polled) + Date.now() - polledAt : clock.now()),
    [polled, polledAt, clock],
  );
  const clockReady = polled !== undefined || freshness.failed;
  const list = useInfiniteQuery(listQuery(period, filters, businessNow, clockReady));
  const chart = useQuery(chartQuery(period, filters, businessNow, clockReady));
  const routes = useQuery(routesQuery);
  const items = useMemo(() => list.data?.pages.flatMap((page) => page.data.items) ?? [], [list.data]);
  const asOf = list.data?.pages[0]?.asOf;

  const routeLooks = useMemo(
    () =>
      new Map<string, RouteLook>(
        (routes.data?.data.items ?? []).map((route) => [
          route.routeId,
          {
            displayName: route.shortName ?? route.longName ?? route.routeId,
            color: route.color,
            textColor: route.textColor,
          },
        ]),
      ),
    [routes.data],
  );
  const salePoints = useMemo(() => {
    const seen = new Map<string, string | undefined>();
    for (const item of items) if (!seen.has(item.salePointId)) seen.set(item.salePointId, item.salePointName);
    return [...seen].map(([id, name]) => ({ id, ...(name ? { name } : {}) }));
  }, [items]);

  const setSearch = useCallback(
    (change: Partial<TicketingSearch>, replace = true) => {
      void navigate({ search: (previous) => ({ ...ticketingSearch.parse(previous), ...change }), replace });
    },
    [navigate],
  );
  const open = useCallback(
    (anomaly: Anomaly) => {
      setSearch({ anomaly: anomaly.id }, false);
    },
    [setSearch],
  );
  const filterSalePoint = useCallback(
    (salePoint: string) => {
      setSearch({ salePoint });
    },
    [setSearch],
  );

  // New anomalies at the head of the list: tinted for 3 s, or counted behind the pill when scrolled (§6).
  const scroller = useRef<HTMLDivElement>(null);
  const [seen, setSeen] = useState<{ key: string; ids: Set<string> }>({ key: '', ids: new Set() });
  const [fresh, setFresh] = useState<ReadonlySet<string>>(new Set());
  const [pill, setPill] = useState(0);
  const [scrolled, setScrolled] = useState(false);
  const listKey = JSON.stringify(list.data ? [period, filters] : null);
  // Compared during render (no effect): rows above the first one already seen are new.
  if (list.data && !list.isPlaceholderData && (seen.key !== listKey || items.some((item) => !seen.ids.has(item.id)))) {
    if (seen.key === listKey) {
      const added = newHeadIds(seen.ids, items);
      if (added.length > 0) {
        setFresh(new Set(added));
        if (scrolled) setPill((n) => n + added.length);
      }
    } else {
      setPill(0);
    }
    setSeen({ key: listKey, ids: new Set(items.map((item) => item.id)) });
  }
  useEffect(() => {
    if (fresh.size === 0) return;
    const timer = setTimeout(() => {
      setFresh(new Set());
    }, HIGHLIGHT_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [fresh]);

  const ticketing = freshness.data?.sources.find((source) => source.source === 'TICKETING_SALES');
  const staleAge =
    ticketing?.stale && ticketing.ageSeconds !== undefined ? formatDuration(ticketing.ageSeconds * 1000) : undefined;
  const long = period.spanMs > DAY_MS;
  const clearFilters = () => {
    setSearch(CLEARED);
  };

  const subtitle = (
    <>
      {copy.subtitle}
      {period.kind === 'fixed' ? (
        <>
          {' · '}
          {formatDateTime(period.from, { timeZone: clock.timezone, showZone: false })}
          {' – '}
          {formatDateTime(period.to, { timeZone: clock.timezone })}
        </>
      ) : null}
      {asOf ? (
        <>
          {' · '}
          {en.time.asOf(formatTime(asOf, { timeZone: clock.timezone }))}
        </>
      ) : null}
    </>
  );

  const empty = filtered ? (
    <EmptyState
      icon={SearchX}
      title={copy.empty.filtered}
      action={{ label: copy.filters.clearFilters, onClick: clearFilters }}
    />
  ) : (
    <EmptyState title={copy.empty.title} description={copy.empty.description} />
  );

  const listPanel = (
    <section
      aria-label={copy.table.caption}
      className="flex min-h-0 flex-col rounded-lg border border-border bg-card shadow-sm"
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-border px-4 py-3">
        <h2 className="text-base font-semibold tracking-title">
          {copy.filters.count(`${formatCount(items.length)}${list.hasNextPage ? '+' : ''}`)}
        </h2>
        <TicketingFilters search={search} salePoints={salePoints} onSearch={setSearch} />
      </div>
      {pill > 0 ? (
        <div className="flex justify-center px-3 pt-3">
          <NewItemsPill
            count={pill}
            noun={copy.newItems}
            onShow={() => {
              scroller.current?.scrollTo({ top: 0 });
              setPill(0);
            }}
          />
        </div>
      ) : null}
      <div
        ref={scroller}
        onScroll={(event) => {
          const down = event.currentTarget.scrollTop > PILL_AFTER_PX;
          if (down !== scrolled) setScrolled(down);
          if (!down && pill > 0) setPill(0);
        }}
        className="min-h-0 overflow-y-auto lg:max-h-[calc(100svh-22rem)]"
      >
        {list.isError && items.length === 0 ? (
          <div className="p-4">
            <ErrorState
              error={list.error}
              variant="block"
              panel={copy.table.panel}
              onRetry={() => void list.refetch()}
            />
          </div>
        ) : (
          <AnomalyTable
            items={items}
            isLoading={list.isPending}
            dimmed={list.isPlaceholderData}
            {...(search.anomaly ? { selectedId: search.anomaly } : {})}
            fresh={fresh}
            long={long}
            routes={routeLooks}
            empty={empty}
            onOpen={open}
            onSalePoint={filterSalePoint}
          />
        )}
        {list.hasNextPage ? (
          <div className="flex justify-center border-t border-border p-3">
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
      </div>
    </section>
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[{ label: en.nav.groups.operations }, { label: copy.crumb }]}
        title={copy.title}
        subtitle={subtitle}
        actions={
          <SegmentedControl<Window>
            label={copy.window.label}
            value={period.kind === 'window' ? period.window : ('' as Window)}
            options={WINDOWS.map((value) => ({ value, label: copy.window.options[value] }))}
            onChange={(value) => {
              setSearch({ window: value === '24h' ? undefined : value, from: undefined, to: undefined });
            }}
          />
        }
      />

      {ticketing?.stale ? (
        <Callout
          tone="warning"
          title={staleAge ? copy.stale.title(staleAge) : copy.stale.titleUnknown}
          action={
            <Button asChild variant="outline" size="sm">
              <AppLink href={CHECK_SOURCE}>{copy.stale.check}</AppLink>
            </Button>
          }
        >
          {copy.stale.body}
        </Callout>
      ) : null}

      <AnomalyChart
        {...(chart.data ? { data: chart.data } : {})}
        error={chart.error}
        onRetry={() => void chart.refetch()}
        {...(ticketing?.stale && staleAge ? { noData: copy.chart.noData(staleAge) } : {})}
      />

      <SplitView
        detailWidth={400}
        list={listPanel}
        detail={search.anomaly ? <AnomalyDetail key={search.anomaly} id={search.anomaly} routes={routeLooks} /> : null}
        emptyDetail={<EmptyState title={copy.detail.select} />}
      />
    </div>
  );
}
