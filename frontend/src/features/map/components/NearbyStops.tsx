import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { AlertTriangle } from 'lucide-react';

import { FreshnessIndicator } from '@/components/FreshnessIndicator';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { distanceMeters, routeName, type RouteItem, type StopItem } from '@/features/map/model';
import { NEARBY_LIMIT, nearbyArrivalsQuery, nearbyStopsQuery } from '@/features/map/queries';
import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { mapCopy } from '@/i18n/map';
import { useBusinessClock } from '@/lib/business-clock';
import { formatEta } from '@/lib/format';
import type { StopRef } from '@/lib/stop-lists';

// "Nearby stops" of the mobile sheet (screens/live-map §4.2): stops within about 400 m of the user, nearest first,
// with their next three arrivals; without a location, the saved and recent stops.

const copy = mapCopy.map.mobile;

export type Location =
  | { state: 'off' }
  | { state: 'locating' }
  | { state: 'denied' }
  | { state: 'found'; lat: number; lon: number; bbox: string };

interface NearbyStopsProps {
  location: Location;
  routes: ReadonlyMap<string, RouteItem>;
  saved: readonly StopRef[];
  recent: readonly StopRef[];
  /** The newest vehicle fix, for the freshness line. */
  asOf: string | undefined;
}

export function NearbyHeader({ asOf }: { asOf: string | undefined }) {
  return (
    <div className="flex items-baseline justify-between px-4 pb-3">
      <div>
        <h2 className="text-[19px] font-semibold tracking-[-0.025em]">{copy.nearbyStops}</h2>
        <FreshnessIndicator asOf={asOf} axis="event" staleAfterSeconds={90} />
      </div>
      <span className="text-[13px] text-muted-foreground">{copy.within}</span>
    </div>
  );
}

export function NearbyStops({ location, routes, saved, recent, asOf }: NearbyStopsProps) {
  return (
    <>
      <NearbyHeader asOf={asOf} />
      {location.state === 'found' ? (
        <Located lat={location.lat} lon={location.lon} bbox={location.bbox} routes={routes} />
      ) : location.state === 'locating' ? (
        <p className="px-4 py-3 text-sm text-muted-foreground" role="status">
          {copy.locating}
        </p>
      ) : (
        <>
          {location.state === 'denied' ? (
            <p className="px-4 pb-3 text-sm text-tone-warning-fg" role="status">
              {copy.locationOff}
            </p>
          ) : null}
          <StopLinks title={copy.savedStops} stops={saved} />
          <StopLinks title={copy.recentStops} stops={recent} />
          {saved.length === 0 && recent.length === 0 ? (
            <p className="px-4 py-3 text-sm text-muted-foreground">{copy.noSaved}</p>
          ) : null}
        </>
      )}
    </>
  );
}

function StopLinks({ title, stops }: { title: string; stops: readonly StopRef[] }) {
  if (stops.length === 0) return null;
  return (
    <section aria-label={title}>
      <h3 className="px-4 pt-2 pb-1 text-xs font-medium text-muted-foreground">{title}</h3>
      <ul>
        {stops.map((stop) => (
          <li key={stop.stopId} className="border-t border-border">
            <Link
              to="/stops/$stopId"
              params={{ stopId: stop.stopId }}
              className="flex min-h-11 items-center px-4 py-3 text-[15px] font-medium"
            >
              {stop.name}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Located({
  lat,
  lon,
  bbox,
  routes,
}: {
  lat: number;
  lon: number;
  bbox: string;
  routes: ReadonlyMap<string, RouteItem>;
}) {
  const query = useQuery(nearbyStopsQuery(bbox));
  if (query.isPending) return <PanelSkeleton variant="list" rows={3} />;
  const nearest = (query.data?.data.items ?? [])
    .map((stop) => ({ stop, meters: distanceMeters({ lat, lon }, stop) }))
    .sort((a, b) => a.meters - b.meters)
    .slice(0, NEARBY_LIMIT);
  if (nearest.length === 0) return <p className="px-4 py-3 text-sm text-muted-foreground">{copy.noNearby}</p>;
  return (
    <ul>
      {nearest.map(({ stop, meters }) => (
        <NearbyStop key={stop.stopId} stop={stop} meters={meters} routes={routes} />
      ))}
    </ul>
  );
}

function NearbyStop({
  stop,
  meters,
  routes,
}: {
  stop: StopItem;
  meters: number;
  routes: ReadonlyMap<string, RouteItem>;
}) {
  const clock = useBusinessClock();
  const arrivals = useQuery(nearbyArrivalsQuery(stop.stopId));
  const detail = useQuery({
    queryKey: keys.stops.detail(stop.stopId),
    queryFn: () => read(api.GET('/api/v1/stops/{stopId}', { params: { path: { stopId: stop.stopId } } })),
  });
  const warning = detail.data?.data.activeDisruptions[0];
  return (
    <li className="border-t border-border">
      <Link to="/stops/$stopId" params={{ stopId: stop.stopId }} className="flex min-h-11 flex-col gap-2.5 px-4 py-3.5">
        <span className="flex items-baseline justify-between gap-3">
          <span className="text-base font-semibold tracking-[-0.015em]">{stop.name}</span>
          <span className="text-[13px] text-muted-foreground tabular-nums">
            {copy.distance(Math.round(meters / 10) * 10)}
          </span>
        </span>
        {arrivals.data && arrivals.data.data.items.length > 0 ? (
          <span className="flex flex-wrap gap-2">
            {arrivals.data.data.items.map((arrival) => {
              const route = routes.get(arrival.routeId);
              return (
                <span
                  key={`${arrival.tripId}-${arrival.predictedArrival}`}
                  className="inline-flex h-9 items-center gap-1.5 rounded-[10px] border border-border bg-surface pr-2.5 pl-1.5 text-[15px] font-semibold tabular-nums"
                >
                  <RouteBadge
                    routeId={arrival.routeId}
                    displayName={routeName(arrival.routeId, routes)}
                    {...(route?.color ? { color: route.color } : {})}
                    {...(route?.textColor ? { textColor: route.textColor } : {})}
                    size="sm"
                  />
                  {formatEta(arrival.predictedArrival, clock.now(), clock.timezone)}
                </span>
              );
            })}
          </span>
        ) : null}
        {warning ? (
          <span className="flex items-center gap-1.5 text-[12.5px] text-tone-warning-fg">
            <AlertTriangle className="size-3.5" aria-hidden="true" />
            {warning.title}
          </span>
        ) : null}
      </Link>
    </li>
  );
}
