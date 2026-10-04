import { useQueries, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { Map as MapIcon } from 'lucide-react';
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type { WithAsOf } from '@/api/client';
import { keys } from '@/api/keys';
import { hasRole, useAccess } from '@/app/access';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { Button } from '@/components/ui/button';
import { BottomSheet, type SheetSnap } from '@/features/map/components/BottomSheet';
import { ControlPanel, DisruptionChip, MapChip, RouteChips } from '@/features/map/components/ControlPanel';
import { MapCanvas, MapPing, MapPopup } from '@/features/map/components/MapCanvas';
import { MapSearch } from '@/features/map/components/MapSearch';
import { NearbyStops, type Location } from '@/features/map/components/NearbyStops';
import { EmptyVehicles, Legend, MapControls, SummaryPill } from '@/features/map/components/overlays';
import { PanelBar, type PanelKind } from '@/features/map/components/panel-parts';
import type { MapHandle, MapPick } from '@/features/map/handle';
import {
  bboxAround,
  boundsOf,
  pointFeatures,
  routeBounds,
  routeColorOf,
  routeFeatures,
  visibleVehicles,
  type LiveVehicle,
  type PatternStop,
  type RouteDetail,
  type RouteItem,
  type StopItem,
} from '@/features/map/model';
import { usePalette } from '@/features/map/palette';
import {
  bunchingDetailQuery,
  disruptionDetailQuery,
  liveVehiclesQuery,
  openDisruptionsQuery,
  routeDetailQuery,
  routeStopsQuery,
  routesQuery,
} from '@/features/map/queries';
import { formatCamera, type ColourMode, type MapSearch as MapSearchParams } from '@/features/map/search';
import { useMapToasts } from '@/features/map/use-map-toasts';
import { useVehicleLayers } from '@/features/map/use-vehicle-layers';
import { mapCopy } from '@/i18n/map';
import { useMediaQuery } from '@/lib/browser';
import { useBusinessClock } from '@/lib/business-clock';
import { useStopLists } from '@/lib/stop-lists';
import { cn } from '@/lib/utils';
import { useRealtime } from '@/realtime/useRealtime';

// The live map (DOC-36 screens/live-map, prototypes live-map.html and live-map-mobile.html): MapCanvas fills the
// content area and every control floats over it; on phones a bottom sheet holds the nearby stops and the panels.

const copy = mapCopy.map;
const MD = '(min-width: 768px)';
const CAMERA_DEBOUNCE_MS = 500;
const NEARBY_RADIUS_M = 400;
const NO_ROUTES: readonly RouteItem[] = [];

// Not needed for the first paint of the map: the panels load on the first selection, the table with `view=list`.
const PanelContent = lazy(() =>
  import('@/features/map/components/PanelContent').then((module) => ({ default: module.PanelContent })),
);
const VehicleList = lazy(() =>
  import('@/features/map/components/VehicleList').then((module) => ({ default: module.VehicleList })),
);

interface Selection {
  kind: PanelKind;
  id: string;
}

interface StopPin {
  stopId: string;
  name: string;
  lon: number;
  lat: number;
}

/** Stable output of `useQueries`, so that effects on it run only when a route actually loads. */
function loadedDetails(results: UseQueryResult<WithAsOf<RouteDetail>>[]) {
  return {
    details: results.flatMap((result) => (result.data ? [result.data.data] : [])),
    failed: results.flatMap((result, index) => (result.isError ? [index] : [])),
  };
}

function loadedStops(results: UseQueryResult<WithAsOf<{ items: StopItem[] }>>[]) {
  return results.flatMap((result) => result.data?.data.items ?? []);
}

export function LiveMapPage({ search }: { search: MapSearchParams }) {
  const navigate = useNavigate({ from: '/map' });
  const queryClient = useQueryClient();
  const clock = useBusinessClock();
  const access = useAccess();
  const staff = hasRole(access, 'viewer');
  const operator = access.role === 'operator';
  const tablet = useMediaQuery(MD, true);

  const routeKey = (search.route ?? []).join(',');
  const routeIds = useMemo(() => (routeKey ? routeKey.split(',') : []), [routeKey]);
  const mode: ColourMode = search.colour ?? 'delay';
  const view = search.view ?? 'map';
  // `bunching` means nothing to anonymous users: they have no overlay data (screens/live-map §2).
  const bunchingId = staff ? search.bunching : undefined;
  const selection = useMemo<Selection | undefined>(
    () =>
      search.vehicle
        ? { kind: 'vehicle', id: search.vehicle }
        : bunchingId
          ? { kind: 'bunching', id: bunchingId }
          : search.disruption
            ? { kind: 'disruption', id: search.disruption }
            : undefined,
    [search.vehicle, bunchingId, search.disruption],
  );
  const [sheet, setSheet] = useState<SheetSnap>(selection ? 'half' : 'peek');

  useRealtime({ channels: ['vehicles', 'alerts'], routeIds });

  const update = useCallback(
    (change: Partial<MapSearchParams>, replace: boolean) => {
      void navigate({ search: (previous: MapSearchParams) => ({ ...previous, ...change }), replace });
    },
    [navigate],
  );
  const select = useCallback(
    (next: Selection | undefined) => {
      if (next) setSheet('half');
      update(
        {
          vehicle: next?.kind === 'vehicle' ? next.id : undefined,
          bunching: next?.kind === 'bunching' ? next.id : undefined,
          disruption: next?.kind === 'disruption' ? next.id : undefined,
        },
        false,
      );
    },
    [update],
  );

  // ---------------------------------------------------------------------------------------------------------------
  // Data

  const routesResult = useQuery(routesQuery());
  const routeList = routesResult.data?.data.items ?? NO_ROUTES;
  const byId = useMemo(() => new Map(routeList.map((route) => [route.routeId, route])), [routeList]);

  const { details, failed } = useQueries({
    queries: routeIds.map((routeId) => routeDetailQuery(routeId)),
    combine: loadedDetails,
  });
  const failedRoutes = useMemo(() => new Set(failed.map((index) => routeIds[index] ?? '')), [failed, routeIds]);
  const routeStops = useQueries({ queries: routeIds.map((routeId) => routeStopsQuery(routeId)), combine: loadedStops });
  const openDisruptions = useQuery(openDisruptionsQuery(routeIds)).data?.data.items;
  const disruptions = useMemo(() => openDisruptions ?? [], [openDisruptions]);

  const disruption = useQuery({
    ...disruptionDetailQuery(selection?.kind === 'disruption' ? selection.id : ''),
    enabled: selection?.kind === 'disruption',
  }).data?.data;
  const disruptionRoute = useQuery({
    ...routeDetailQuery(disruption?.routeId ?? ''),
    enabled: disruption !== undefined,
  }).data?.data;
  const bunching = useQuery({
    ...bunchingDetailQuery(selection?.kind === 'bunching' ? selection.id : ''),
    enabled: selection?.kind === 'bunching',
  }).data?.data;
  const focus = useMemo(
    () => (bunching ? new Set([bunching.vehicleLeader, bunching.vehicleFollower]) : undefined),
    [bunching],
  );

  const alerts = useMapToasts({
    routeIds,
    staff,
    routes: byId,
    onShowDisruption: (id) => {
      select({ kind: 'disruption', id });
    },
    onShowBunching: (id) => {
      select({ kind: 'bunching', id });
    },
  });
  const alertFor = (refId: string) => alerts?.find((alert) => alert.refId === refId)?.id;

  // ---------------------------------------------------------------------------------------------------------------
  // Map

  const [handle, setHandle] = useState<MapHandle>();
  const palette = usePalette();
  // "Follow" belongs to one bus: opening another one starts without it.
  const [followed, setFollowed] = useState<string>();
  const following = followed !== undefined && followed === search.vehicle;
  const setFollowing = (follow: boolean) => {
    setFollowed(follow ? search.vehicle : undefined);
  };
  const [stopPin, setStopPin] = useState<StopPin>();
  const [legendOpen, setLegendOpen] = useState(tablet);

  useVehicleLayers(handle, routeIds, {
    mode,
    palette,
    routes: byId,
    showBunching: staff,
    ...(search.vehicle ? { selectedVehicle: search.vehicle } : {}),
    ...(focus ? { focus } : {}),
    ...(following && handle
      ? {
          onSelectedMoved: (vehicle: LiveVehicle) => {
            handle.moveTo(vehicle.lon, vehicle.lat);
          },
        }
      : {}),
  });

  // Stops of every route known to the screen, by id: the selected routes and the route of an open disruption.
  const patternStops = useMemo(() => {
    const stops = new Map<string, PatternStop>();
    for (const route of disruptionRoute ? [...details, disruptionRoute] : details) {
      for (const direction of route.directions) for (const stop of direction.stops) stops.set(stop.stopId, stop);
    }
    return stops;
  }, [details, disruptionRoute]);

  const affectedStops = useMemo(() => {
    const ids = new Set(disruptions.flatMap((episode) => episode.affectedStopIds));
    for (const id of disruption?.affectedStopIds ?? []) ids.add(id);
    return [...ids].flatMap((id) => {
      const stop = patternStops.get(id);
      return stop ? [stop] : [];
    });
  }, [disruptions, disruption, patternStops]);

  useEffect(() => {
    if (!handle) return;
    handle.set('routes', routeFeatures(details, palette.primary));
  }, [handle, palette, details]);

  useEffect(() => {
    if (!handle) return;
    handle.set(
      'stops',
      pointFeatures(routeStops, (stop) => ({
        id: stop.stopId,
        name: stop.name,
        selected: stop.stopId === stopPin?.stopId,
        colour: routeColorOf(byId.get(stop.routeIds.find((id) => routeIds.includes(id)) ?? ''), palette.primary),
      })),
    );
  }, [handle, palette, routeStops, stopPin, byId, routeIds]);

  useEffect(() => {
    handle?.set(
      'disruption-stops',
      pointFeatures(affectedStops, (stop) => ({ id: stop.stopId })),
    );
  }, [handle, affectedStops]);

  // Where the selected bus or pair is: the camera waits for them when the snapshot comes after the selection.
  const selectedIds = useMemo(
    () => (focus ? [...focus] : selection?.kind === 'vehicle' ? [selection.id] : []),
    [focus, selection],
  );
  const selectBounds = useCallback(
    (snapshot: WithAsOf<{ items: LiveVehicle[] }>) =>
      boundsOf(
        snapshot.data.items
          .filter((vehicle) => selectedIds.includes(vehicle.vehicleId))
          .map((vehicle) => [vehicle.lon, vehicle.lat]),
      ),
    [selectedIds],
  );
  const selectedBounds = useQuery({ ...liveVehiclesQuery(routeIds), select: selectBounds }).data;

  // Camera: the object of the URL first, then the selected routes, else the feed area (screens/live-map §2).
  const focused = useRef<string>(undefined);
  const opened = useRef(selection ? `${selection.kind}:${selection.id}` : undefined);
  useEffect(() => {
    if (!handle || !selection) return;
    const key = `${selection.kind}:${selection.id}`;
    if (focused.current === key) return;
    if (selection.kind === 'vehicle') {
      // A bus picked on the map keeps the camera; the one of the URL the page opened with is brought into view.
      if (key !== opened.current) {
        focused.current = key;
        return;
      }
      if (!selectedBounds) return;
      handle.moveTo(selectedBounds[0], selectedBounds[1], Math.max(handle.camera().zoom, 14));
    } else if (selection.kind === 'bunching') {
      if (!bunching || !selectedBounds) return;
      handle.fitBounds(selectedBounds, 120);
    } else {
      if (!disruption) return;
      const bounds = boundsOf(
        disruption.affectedStopIds.flatMap((id) => {
          const stop = patternStops.get(id);
          return stop ? [[stop.lon, stop.lat]] : [];
        }),
      );
      if (!bounds) return;
      handle.fitBounds(bounds, 80);
    }
    focused.current = key;
  }, [handle, selection, bunching, selectedBounds, disruption, patternStops]);

  // A route picked by the user brings its shape into view once loaded; so does `route` on a link without `c`.
  const fitRoutes = useRef(!search.c && !selection && routeIds.length > 0);
  useEffect(() => {
    if (!handle || !fitRoutes.current || details.length < routeIds.length || routeIds.length === 0) return;
    const bounds = routeBounds(details);
    if (bounds) handle.fitBounds(bounds);
    fitRoutes.current = false;
  }, [handle, details, routeIds.length]);

  const cameraTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const onMoveEnd = useCallback(
    (camera: { lon: number; lat: number; zoom: number }) => {
      clearTimeout(cameraTimer.current);
      cameraTimer.current = setTimeout(() => {
        update({ c: formatCamera(camera) }, true);
      }, CAMERA_DEBOUNCE_MS);
    },
    [update],
  );
  useEffect(
    () => () => {
      clearTimeout(cameraTimer.current);
    },
    [],
  );

  const vehicleById = (id: string) =>
    queryClient
      .getQueryData<WithAsOf<{ items: LiveVehicle[] }>>(keys.vehicles.live(routeIds))
      ?.data.items.find((vehicle) => vehicle.vehicleId === id);

  const onPick = (pick: MapPick | undefined) => {
    if (!pick) {
      setStopPin(undefined);
      return;
    }
    const id = typeof pick.properties.id === 'string' ? pick.properties.id : undefined;
    if (pick.layer === 'vehicle-clusters') {
      const clusterId = Number(pick.properties.cluster_id);
      void handle?.clusterZoom(clusterId).then((zoom) => {
        handle.moveTo(pick.lon, pick.lat, zoom);
      });
    } else if (pick.layer === 'bunching-halo' && staff && typeof pick.properties.episodeId === 'string') {
      select({ kind: 'bunching', id: pick.properties.episodeId });
    } else if (pick.layer === 'vehicles' && id) {
      const episode = staff ? vehicleById(id)?.bunching?.episodeId : undefined;
      select(episode ? { kind: 'bunching', id: episode } : { kind: 'vehicle', id });
    } else if (pick.layer === 'stops' && id) {
      const name = typeof pick.properties.name === 'string' ? pick.properties.name : '';
      setStopPin({ stopId: id, name, lon: pick.lon, lat: pick.lat });
    }
  };

  const onLocate = () => {
    const vehicles = visibleVehicles(
      queryClient.getQueryData<WithAsOf<{ items: LiveVehicle[] }>>(keys.vehicles.live(routeIds))?.data.items ?? [],
      clock.now(),
    );
    const bounds = boundsOf(vehicles.map((vehicle) => [vehicle.lon, vehicle.lat]));
    if (bounds) handle?.fitBounds(bounds);
  };

  const setRoutes = (next: string[]) => {
    fitRoutes.current = next.length > 0;
    update({ route: next.length > 0 ? next : undefined }, true);
  };
  const addRoute = (route: RouteItem) => {
    if (!routeIds.includes(route.routeId)) setRoutes([...routeIds, route.routeId]);
  };
  const showStop = (stop: StopItem) => {
    handle?.moveTo(stop.lon, stop.lat, 16);
    setStopPin({ stopId: stop.stopId, name: stop.name, lon: stop.lon, lat: stop.lat });
  };
  const setMode = (next: ColourMode) => {
    update({ colour: next === 'delay' ? undefined : next }, true);
  };

  // ---------------------------------------------------------------------------------------------------------------
  // Mobile

  const [location, setLocation] = useState<Location>({ state: 'off' });
  const lists = useStopLists();
  const locate = () => {
    if (!('geolocation' in navigator)) {
      setLocation({ state: 'denied' });
      return;
    }
    setLocation({ state: 'locating' });
    setSheet('half');
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude: lat, longitude: lon } = position.coords;
        setLocation({ state: 'found', lat, lon, bbox: bboxAround(lat, lon, NEARBY_RADIUS_M) });
        handle?.moveTo(lon, lat, 15);
      },
      () => {
        setLocation({ state: 'denied' });
      },
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 60_000 },
    );
  };
  const newestFix = useQuery({
    ...liveVehiclesQuery(routeIds),
    select: (snapshot) =>
      snapshot.data.items.reduce<string | undefined>(
        (latest, vehicle) =>
          latest === undefined || vehicle.eventTimestamp > latest ? vehicle.eventTimestamp : latest,
        undefined,
      ),
  }).data;

  // ---------------------------------------------------------------------------------------------------------------
  // Render

  const panel = selection ? (
    <Suspense fallback={<PanelSkeleton variant="detail" />}>
      <PanelContent
        selection={selection}
        routeIds={routeIds}
        routes={byId}
        staff={staff}
        operator={operator}
        following={following}
        onFollow={setFollowing}
        alertId={selection.kind === 'vehicle' ? undefined : alertFor(selection.id)}
        onExpired={() => {
          update({ disruption: undefined }, true);
        }}
      />
    </Suspense>
  ) : null;

  if (view === 'list') {
    return (
      <div className="flex flex-1 flex-col gap-4 px-4 py-5 md:px-7 md:py-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-xl font-semibold tracking-tight">{copy.title}</h1>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              update({ view: undefined }, false);
            }}
          >
            <MapIcon aria-hidden="true" />
            {copy.controls.mapView}
          </Button>
        </div>
        <RouteChips routes={routeList} byId={byId} selected={routeIds} failed={failedRoutes} onChange={setRoutes} />
        <Suspense fallback={<PanelSkeleton variant="table" />}>
          <VehicleList
            routeIds={routeIds}
            routes={routeList}
            byId={byId}
            onOpen={(vehicle) => {
              update(
                { view: undefined, vehicle: vehicle.vehicleId, bunching: undefined, disruption: undefined },
                false,
              );
            }}
          />
        </Suspense>
      </div>
    );
  }

  const canvas = (
    <MapCanvas
      initialCamera={search.c}
      onReady={setHandle}
      onMoveEnd={onMoveEnd}
      onUserMove={() => {
        setFollowing(false);
      }}
      onPick={onPick}
    >
      {search.vehicle ? <SelectedPing vehicleId={search.vehicle} routeIds={routeIds} /> : null}
      {disruption && affectedStops.length > 0 ? (
        <DisruptionPing stop={patternStops.get(disruption.affectedStopIds[0] ?? '')} />
      ) : null}
      {stopPin ? (
        <MapPopup
          lon={stopPin.lon}
          lat={stopPin.lat}
          onClose={() => {
            setStopPin(undefined);
          }}
        >
          <p className="text-sm font-medium">{stopPin.name}</p>
          <Link
            to="/stops/$stopId"
            params={{ stopId: stopPin.stopId }}
            className="mt-1 inline-block text-sm text-primary underline-offset-4 hover:underline"
          >
            {copy.controls.stopDetails}
          </Link>
        </MapPopup>
      ) : null}
    </MapCanvas>
  );

  const controls = (
    <MapControls
      touch={!tablet}
      legendOpen={legendOpen}
      onToggleLegend={() => {
        setLegendOpen(!legendOpen);
      }}
      onZoomIn={() => handle?.zoomBy(1)}
      onZoomOut={() => handle?.zoomBy(-1)}
      onLocate={onLocate}
      onListView={() => {
        update({ view: 'list' }, false);
      }}
      className={tablet ? cn('bottom-4 z-10', selection ? 'right-[412px]' : 'right-4') : 'top-[128px] right-3 z-10'}
    />
  );

  const legend = (
    <Legend
      routeIds={routeIds}
      mode={mode}
      routes={byId}
      staff={staff}
      disruptions={disruptions.length}
      open={legendOpen}
      onToggle={() => {
        setLegendOpen(!legendOpen);
      }}
      {...(tablet ? {} : { onModeChange: setMode })}
      className={tablet ? 'bottom-4 left-4 z-10' : 'top-[128px] left-3 z-10'}
    />
  );

  return (
    <div className="relative flex-1 overflow-hidden">
      <h1 className="sr-only">{copy.title}</h1>
      {canvas}
      {tablet ? (
        <>
          <ControlPanel
            routes={routeList}
            byId={byId}
            selected={routeIds}
            failed={failedRoutes}
            onRoutesChange={setRoutes}
            onRoute={addRoute}
            onStop={showStop}
            mode={mode}
            onModeChange={setMode}
            disruptions={disruptions}
            onDisruption={(episode) => {
              select({ kind: 'disruption', id: episode.id });
            }}
          />
          <SummaryPill
            routeIds={routeIds}
            className={cn('top-4 z-10', selection ? 'left-[372px]' : 'left-1/2 -translate-x-1/2')}
          />
          <EmptyVehicles routeIds={routeIds} routes={byId} />
          {legend}
          {controls}
          {selection ? (
            <aside
              aria-label={copy.panel.kind[selection.kind]}
              className="absolute top-4 right-4 z-10 flex max-h-[calc(100%-2rem)] w-[380px] flex-col overflow-hidden rounded-xl border border-border bg-card shadow-md"
            >
              <PanelBar
                kind={selection.kind}
                onClose={() => {
                  select(undefined);
                }}
              />
              <div
                tabIndex={0}
                className="min-h-0 flex-1 overflow-y-auto outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {panel}
              </div>
            </aside>
          ) : null}
        </>
      ) : (
        <>
          <div className="absolute inset-x-3 top-3.5 z-10 flex flex-col gap-2">
            <MapSearch
              routes={routeList}
              onRoute={addRoute}
              onStop={showStop}
              placeholder={copy.controls.searchMobile}
              large
            />
            <div className="flex gap-2 overflow-x-auto pb-1">
              <MapChip active={location.state === 'found'} onClick={locate}>
                {copy.mobile.nearby}
              </MapChip>
              <RouteChips
                routes={routeList}
                byId={byId}
                selected={routeIds}
                failed={failedRoutes}
                onChange={setRoutes}
                className="shrink-0 flex-nowrap"
              />
              <MapChip
                active={false}
                onClick={() => {
                  setLocation({ state: 'off' });
                  setSheet('half');
                  select(undefined);
                }}
              >
                {copy.mobile.saved}
              </MapChip>
            </div>
            {disruptions.length > 0 ? (
              <div className="w-fit">
                <DisruptionChip
                  disruptions={disruptions}
                  byId={byId}
                  onSelect={(episode) => {
                    select({ kind: 'disruption', id: episode.id });
                  }}
                />
              </div>
            ) : null}
          </div>
          <SummaryPill routeIds={routeIds} className="bottom-[108px] left-1/2 z-10 -translate-x-1/2" />
          {legendOpen ? legend : null}
          {controls}
          <BottomSheet
            snap={sheet}
            onSnapChange={setSheet}
            label={selection ? copy.panel.kind[selection.kind] : copy.mobile.nearbyStops}
          >
            {selection ? (
              <>
                <PanelBar
                  kind={selection.kind}
                  onClose={() => {
                    select(undefined);
                    setSheet('peek');
                  }}
                />
                {panel}
              </>
            ) : (
              <NearbyStops
                location={location}
                routes={byId}
                saved={lists.saved}
                recent={lists.recent}
                asOf={newestFix}
              />
            )}
          </BottomSheet>
        </>
      )}
    </div>
  );
}

/** The ping around the selected bus, following it. */
function SelectedPing({ vehicleId, routeIds }: { vehicleId: string; routeIds: readonly string[] }) {
  const position = useQuery({
    ...liveVehiclesQuery(routeIds),
    select: (snapshot) => {
      const vehicle = snapshot.data.items.find((item) => item.vehicleId === vehicleId);
      return vehicle ? { lon: vehicle.lon, lat: vehicle.lat } : undefined;
    },
  }).data;
  return position ? <MapPing lon={position.lon} lat={position.lat} tone="primary" /> : null;
}

function DisruptionPing({ stop }: { stop: PatternStop | undefined }) {
  return stop ? <MapPing lon={stop.lon} lat={stop.lat} tone="danger" /> : null;
}
