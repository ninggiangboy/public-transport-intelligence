import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { ArrowLeft, BusFront, Map as MapIcon, Star } from 'lucide-react';
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react';

import type { components } from '@/api/generated/schema';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FreshnessIndicator } from '@/components/FreshnessIndicator';
import { PageHeader } from '@/components/PageHeader';
import { RouteBadge } from '@/components/RouteBadge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { directionLabel, directionsOf, visibleArrivals, type Arrival } from '@/features/stops/arrivals';
import { ArrivalRow } from '@/features/stops/components/ArrivalRow';
import { DisruptionCallouts } from '@/features/stops/components/DisruptionCallouts';
import { ReliabilityCard } from '@/features/stops/components/ReliabilityCard';
import { StopFinder } from '@/features/stops/components/StopFinder';
import {
  ARRIVALS_LIMIT,
  ARRIVALS_LIMIT_MORE,
  arrivalsQuery,
  routeDetailQuery,
  stopDetailQuery,
} from '@/features/stops/queries';
import type { StopDetailSearch } from '@/features/stops/search';
import { rememberRecent, storageWorks, useStopLists } from '@/features/stops/stop-lists';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';
import { useBusinessClock } from '@/lib/business-clock';
import { toMillis } from '@/lib/time';
import { cn } from '@/lib/utils';
import { useRealtime } from '@/realtime/useRealtime';

type StopDetail = components['schemas']['StopDetailResponse'];

/** ETAs count down between refetches (DOC-36 screens/stop-detail §5). */
const ETA_TICK_MS = 15_000;
/** "Predictions as of …" turns to warning past this age (§7). */
const PREDICTIONS_STALE_AFTER_S = 2 * 3600;
/** The server refuses more route filters on the stream (DOC-26 §7). */
const MAX_STREAM_ROUTES = 20;

/** The wall clock, re-read every 15 s: the ETAs count down between refetches (§5). */
function useWallClock(): number {
  const [wall, setWall] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => {
      setWall(Date.now());
    }, ETA_TICK_MS);
    return () => {
      clearInterval(timer);
    };
  }, []);
  return wall;
}

// Shown only at stops served in both directions, and not needed for the first paint (DOC-34 §7).
const SegmentedControl = lazy(() =>
  import('@/components/SegmentedControl').then((module) => ({ default: module.SegmentedControl })),
);

/** The value of the direction control that means "both directions". */
const ALL_DIRECTIONS = 'all';

function StopPlate() {
  return (
    <div
      aria-hidden="true"
      className="flex size-13 shrink-0 flex-col items-center justify-center gap-px rounded-[14px] bg-foreground text-card md:size-14"
    >
      <BusFront className="size-5.5" strokeWidth={1.75} />
      <small className="text-[9px] font-semibold tracking-[0.08em] opacity-70">{en.stops.header.plate}</small>
    </div>
  );
}

function StopHeader({ stop }: { stop: StopDetail }) {
  const lists = useStopLists();
  const [canSave] = useState(storageWorks);
  const saved = lists.saved.some((item) => item.stopId === stop.stopId);
  const ref = { stopId: stop.stopId, name: stop.name, ...(stop.code ? { code: stop.code } : {}) };
  const code = stop.code ?? stop.stopId;
  return (
    <header className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
      <div className="flex items-center gap-4">
        <StopPlate />
        <div className="min-w-0">
          <Link
            to="/stops"
            className="mb-1 inline-flex items-center gap-1 text-label font-medium text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="size-3.5" aria-hidden="true" />
            {en.stops.header.back}
          </Link>
          <h1 className="text-[22px] leading-tight font-semibold tracking-title md:text-page">{stop.name}</h1>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-3.5 gap-y-1 text-label text-muted-foreground">
            <span className="rounded-sm bg-muted px-1.75 py-0.5 font-mono text-foreground-2">
              <span className="md:hidden">{en.stops.header.codeMobile(code)}</span>
              <span className="hidden md:inline">{en.stops.header.code(code)}</span>
            </span>
            {stop.wheelchairBoarding === 1 ? <span>{en.stops.header.stepFree}</span> : null}
            <span>{en.stops.header.routes(stop.routes.length)}</span>
          </div>
        </div>
      </div>
      <div className="flex gap-2">
        {canSave ? (
          <Button
            variant="outline"
            aria-pressed={saved}
            onClick={() => {
              lists.toggleSaved(ref);
            }}
          >
            <Star className={cn(saved && 'fill-current text-primary')} aria-hidden="true" />
            {saved ? en.stops.header.saved : en.stops.header.save}
          </Button>
        ) : null}
        <Button asChild>
          <Link to="/map" search={{ c: `${stop.lon.toFixed(4)},${stop.lat.toFixed(4)},16` } as never}>
            <MapIcon aria-hidden="true" />
            {en.stops.header.viewOnMap}
          </Link>
        </Button>
      </div>
    </header>
  );
}

function HeaderSkeleton() {
  return (
    <div aria-busy="true" className="flex items-center gap-4">
      <Skeleton className="size-14 rounded-[14px]" />
      <div className="flex flex-1 flex-col gap-2">
        <Skeleton className="h-6 w-64 max-w-full" />
        <Skeleton className="h-4 w-40" />
      </div>
    </div>
  );
}

function RowsSkeleton() {
  return (
    <div aria-busy="true" className="flex flex-col">
      <span role="status" className="sr-only">
        {en.states.loading}
      </span>
      {[0, 1, 2, 3].map((row) => (
        <Skeleton key={row} className="m-2 h-[56px] rounded-md" />
      ))}
    </div>
  );
}

function RouteChip({
  active,
  onClick,
  children,
  label,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
  label?: string;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      aria-label={label}
      onClick={onClick}
      className={cn(
        'inline-flex h-9 items-center gap-1.5 rounded-full border px-3 text-label font-medium whitespace-nowrap md:h-7 md:px-2.5',
        active
          ? 'border-foreground bg-foreground text-card'
          : 'border-border-strong bg-card text-foreground-2 hover:bg-muted',
      )}
    >
      {children}
    </button>
  );
}

interface DeparturesProps {
  stop: StopDetail;
  search: StopDetailSearch;
}

function Departures({ stop, search }: DeparturesProps) {
  const navigate = useNavigate({ from: '/stops/$stopId' });
  const clock = useBusinessClock();
  const [limit, setLimit] = useState(ARRIVALS_LIMIT);
  const arrivals = useQuery(arrivalsQuery(stop.stopId, limit));
  const body = arrivals.data?.data;
  // businessNow, anchored on E-08's own `businessNow` so that the countdown is right before /system/freshness answers.
  const wall = Math.max(useWallClock(), arrivals.dataUpdatedAt);
  const now = body ? toMillis(body.businessNow) + wall - arrivals.dataUpdatedAt : clock.now();
  const items: Arrival[] = body?.items ?? [];
  const directions = directionsOf(items);
  const firstRoute = items[0]?.routeId;
  const routeDetail = useQuery({
    ...routeDetailQuery(firstRoute ?? ''),
    enabled: directions.length > 1 && firstRoute !== undefined,
  });
  const routes = new Map(stop.routes.map((route) => [route.routeId, route]));
  const selected = search.route ?? [];
  const shown = visibleArrivals(items, now, { routes: selected, dir: search.dir });
  const copy = en.stops.departures;

  const setSearch = (next: StopDetailSearch) => {
    void navigate({ search: next, replace: true });
  };
  const toggleRoute = (routeId: string) => {
    const route = selected.includes(routeId) ? selected.filter((id) => id !== routeId) : [...selected, routeId];
    setSearch({ ...search, route: route.length > 0 ? route : undefined });
  };

  return (
    <section
      aria-labelledby="departures-title"
      className="flex flex-col overflow-hidden rounded-lg border border-border bg-card shadow-sm"
    >
      <div className="flex flex-col gap-3 border-b border-border px-4 pt-4 pb-3.5 md:flex-row md:items-center md:justify-between md:px-4.5">
        <div className="flex items-center justify-between gap-3 md:block">
          <h2 id="departures-title" className="text-panel font-semibold tracking-title">
            {copy.title}
          </h2>
          <div className="md:mt-1.5">
            <FreshnessIndicator
              asOf={arrivals.data?.asOf}
              axis="event"
              mode="absolute"
              staleAfterSeconds={PREDICTIONS_STALE_AFTER_S}
              label={copy.asOf}
            />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {stop.routes.length > 1 ? (
            <div role="group" aria-label={copy.routeFilter} className="flex flex-wrap gap-1.5">
              <RouteChip
                active={selected.length === 0}
                onClick={() => {
                  setSearch({ ...search, route: undefined });
                }}
              >
                {copy.all}
              </RouteChip>
              {stop.routes.map((route) => (
                <RouteChip
                  key={route.routeId}
                  active={selected.includes(route.routeId)}
                  label={en.route.label(route.displayName)}
                  onClick={() => {
                    toggleRoute(route.routeId);
                  }}
                >
                  <RouteBadge
                    routeId={route.routeId}
                    displayName={route.displayName}
                    color={route.color}
                    textColor={route.textColor}
                    size="sm"
                  />
                </RouteChip>
              ))}
            </div>
          ) : null}
          {directions.length > 1 ? (
            <Suspense>
              <SegmentedControl
                size="sm"
                label={copy.directionFilter}
                value={search.dir === undefined ? ALL_DIRECTIONS : String(search.dir)}
                onChange={(value) => {
                  setSearch({ ...search, dir: value === ALL_DIRECTIONS ? undefined : Number(value) });
                }}
                options={[
                  { value: ALL_DIRECTIONS, label: copy.all },
                  ...directions.map((direction) => ({
                    value: String(direction),
                    label: directionLabel(
                      routeDetail.data?.data.directions.find((d) => d.directionId === direction)?.label,
                      direction,
                    ),
                  })),
                ]}
              />
            </Suspense>
          ) : null}
        </div>
      </div>
      {arrivals.isPending ? (
        <RowsSkeleton />
      ) : arrivals.isError && !body ? (
        <ErrorState error={arrivals.error} variant="block" panel={copy.panel} onRetry={() => void arrivals.refetch()} />
      ) : shown.length === 0 ? (
        <EmptyState title={copy.emptyTitle} description={copy.emptyBody} />
      ) : (
        <>
          {arrivals.isError ? (
            <div className="px-4 pt-3">
              <ErrorState
                error={arrivals.error}
                variant="inline"
                dataAsOf={arrivals.data?.asOf}
                onRetry={() => void arrivals.refetch()}
              />
            </div>
          ) : null}
          <ul>
            {shown.map((arrival) => (
              <ArrivalRow
                key={`${arrival.tripId}:${arrival.serviceDate}`}
                arrival={arrival}
                route={routes.get(arrival.routeId) ?? { displayName: arrival.routeId }}
                now={now}
                timeZone={clock.timezone}
              />
            ))}
          </ul>
        </>
      )}
      <footer className="flex flex-col gap-2 border-t border-border bg-surface px-4 py-2.5 text-sm text-muted-foreground md:flex-row md:items-center md:justify-between">
        <span>{copy.note}</span>
        {limit < ARRIVALS_LIMIT_MORE && items.length >= limit ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setLimit(ARRIVALS_LIMIT_MORE);
            }}
          >
            {copy.showLater}
          </Button>
        ) : null}
      </footer>
    </section>
  );
}

function NotFound() {
  const [q, setQ] = useState('');
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-5">
      <PageHeader title={en.stops.notFound.title} subtitle={en.stops.notFound.body} />
      <StopFinder q={q} onQChange={setQ} showLists={false} />
    </div>
  );
}

interface StopDetailPageProps {
  stopId: string;
  search: StopDetailSearch;
}

/** /stops/$stopId: departures, disruptions and reliability at one stop (DOC-36 screens/stop-detail). */
export function StopDetailPage({ stopId, search }: StopDetailPageProps) {
  const clock = useBusinessClock();
  const detail = useQuery(stopDetailQuery(stopId));
  const stop = detail.data?.data;
  const routeIds = stop?.routes.map((route) => route.routeId) ?? [];
  useRealtime({ channels: ['alerts'], routeIds: routeIds.length <= MAX_STREAM_ROUTES ? routeIds : [] });
  useDocumentTitle(stop?.name ?? en.nav.items.stops);

  useEffect(() => {
    if (stop) rememberRecent({ stopId: stop.stopId, name: stop.name, ...(stop.code ? { code: stop.code } : {}) });
  }, [stop]);

  if (detail.isError && !stop) {
    if ((detail.error as { status?: number }).status === 404) return <NotFound />;
    return (
      <>
        <h1 className="sr-only">{en.nav.items.stops}</h1>
        <ErrorState error={detail.error} variant="block" onRetry={() => void detail.refetch()} />
      </>
    );
  }

  return (
    <div className="flex flex-col gap-5">
      {stop ? <StopHeader stop={stop} /> : <HeaderSkeleton />}
      <div className="grid gap-3.5 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] lg:items-start">
        <div className="flex flex-col gap-3.5 lg:col-start-2 lg:row-start-1">
          {stop ? (
            <DisruptionCallouts disruptions={stop.activeDisruptions} routes={stop.routes} timeZone={clock.timezone} />
          ) : null}
        </div>
        <div className="lg:col-start-1 lg:row-span-2 lg:row-start-1">
          {stop ? <Departures stop={stop} search={search} /> : <RowsSkeleton />}
        </div>
        {/* The desktop mini map (screens/stop-detail §4) joins this column with the MapCanvas of the live map (P5-06). */}
        <div className="lg:col-start-2">{stop ? <ReliabilityLoader stop={stop} /> : null}</div>
      </div>
    </div>
  );
}

/** "Reliability here" waits for the departures (E-08 is cached, so this reads the same entry). */
function ReliabilityLoader({ stop }: { stop: StopDetail }) {
  const arrivals = useQuery({ ...arrivalsQuery(stop.stopId, ARRIVALS_LIMIT), refetchInterval: false });
  if (!arrivals.data) return null;
  return <ReliabilityCard stopId={stop.stopId} routes={stop.routes} arrivals={arrivals.data.data.items} />;
}
