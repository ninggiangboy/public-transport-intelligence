import { QueryObserver, useQueries, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

import type { WithAsOf } from '@/api/client';
import type { MapHandle } from '@/features/map/handle';
import {
  isFaded,
  routeName,
  vehicleColour,
  visibleVehicles,
  type LiveVehicle,
  type Palette,
  type RouteDetail,
  type RouteItem,
} from '@/features/map/model';
import { liveVehiclesQuery, routeDetailQuery } from '@/features/map/queries';
import type { ColourMode } from '@/features/map/search';
// eslint-disable-next-line import-x/no-restricted-paths -- the 3D view's type-only contract (DR-111).
import type { CameraMode, Layers3d, Map3dApi, Map3dEvents, RouteShapes, Vehicle3d } from '@/features/map3d/api';
import { mapCopy } from '@/i18n/map';
import { useBusinessClock } from '@/lib/business-clock';
import { notify } from '@/lib/notify';
import { toMillis } from '@/lib/time';
import { useReducedMotion } from '@/lib/use-reduced-motion';

// The 3D view of the live map (DOC-36 screens/live-map §4.3, §7, DR-111): enter/leave the controller, feed it vehicles
// and shapes, keep the layers, the camera mode and the fix age, and fall back to 2D when the chunk fails. Everything
// 3D is reached through `handle.enter3d` (which `import()`s the controller) and the lazily loaded controls, so this
// file stays in the /map chunk.

const copy = mapCopy.map.view3d;

/** `FixInput` lives in the lazy motion module; read its shape from the contract so this file stays non-lazy. */
type FixInput = Parameters<Map3dApi['setVehicles']>[0][number];

const AGE_REDRAW_MS = 5_000;
const ESTIMATE_TICK_MS = 1_000;
// Shapes only help once vehicles are close enough to sit on their route (DR-111; E-02 of up to 20 routes).
const SHAPE_MIN_ZOOM = 14;
const MAX_SHAPE_ROUTES = 20;
const DEFAULT_LAYERS: Layers3d = { buildings: true, lastFix: false };
const NO_ROUTES: readonly string[] = [];

/** `readLayers3d` rides the lazily loaded controls; importing the module statically would drag it into /map (DR-107). */
function readInitialLayers(): Promise<Layers3d> {
  return import('@/features/map/components/Map3dControls').then((module) => module.readLayers3d());
}

function combineDetails(results: UseQueryResult<WithAsOf<RouteDetail>>[]): RouteDetail[] {
  return results.flatMap((result) => (result.data ? [result.data.data] : []));
}

function toRouteShapes(route: RouteDetail): RouteShapes {
  return {
    routeId: route.routeId,
    directions: route.directions.map((direction) => ({
      directionId: direction.directionId,
      coordinates: direction.geometry.coordinates.map((point) => [point[0] ?? 0, point[1] ?? 0] as const),
    })),
  };
}

export type Map3dState = 'idle' | 'loading' | 'ready' | 'failed';

/** What the enter/retry cycle sets; `idle` is derived from the screen's `active` flag so it needs no state of its own. */
type Map3dPhase = Exclude<Map3dState, 'idle'>;

export interface Map3dController {
  state: Map3dState;
  /** Free / Follow / Chase; the vehicle panel writes it. */
  mode: CameraMode;
  setMode: (mode: CameraMode) => void;
  layers: Layers3d;
  setLayers: (layers: Layers3d) => void;
  /** Fix age of the selected vehicle in seconds, refreshed once per second. */
  estimateAge: number | undefined;
  api: Map3dApi | undefined;
  /** Clears a load failure so the screen can enter 3D again (the toast action calls it). */
  retry: () => void;
}

export interface UseMap3dOptions {
  handle: MapHandle | undefined;
  /** `view=3d` on a device that can show it (desktop, WebGL 2, no load failure). */
  active: boolean;
  /** Parsed `c` pitch, or the 58° entry tilt (DR-111). */
  pitch: number;
  routeIds: readonly string[];
  mode: ColourMode;
  palette: Palette;
  routes: ReadonlyMap<string, RouteItem>;
  selectedVehicle?: string | undefined;
  /** Vehicles of a selected bunching pair; the others are dimmed. */
  focus?: ReadonlySet<string> | undefined;
  /** Writes `c` after a camera move; the same debounced handler as the 2D map. */
  onCamera: (camera: { lon: number; lat: number; zoom: number; bearing: number; pitch: number }) => void;
  /** The 3D chunk failed to load or the controller threw. */
  onLoadError: () => void;
  /** Clears the failure so the screen can try again. */
  retry: () => void;
}

export function useMap3d(options: UseMap3dOptions): Map3dController {
  const queryClient = useQueryClient();
  const clock = useBusinessClock();
  const reducedMotion = useReducedMotion();

  const [phase, setPhase] = useState<Map3dPhase>('loading');
  const [api, setApi] = useState<Map3dApi>();
  const [mode, setModeState] = useState<CameraMode>('free');
  const [layers, setLayersState] = useState<Layers3d>(DEFAULT_LAYERS);
  const [estimateAge, setEstimateAge] = useState<number | undefined>(undefined);
  const [viewportRoutes, setViewportRoutes] = useState<readonly string[]>(NO_ROUTES);
  const [generation, setGeneration] = useState(0);

  const apiRef = useRef<Map3dApi | undefined>(undefined);
  /** The vehicle the user chose Follow/Chase for; a plain pick resets to Free (DR-111). */
  const chosenRef = useRef<string | undefined>(undefined);
  const layersRef = useRef(layers);
  const latest = useRef(options);
  const clockRef = useRef(clock);
  const reducedRef = useRef(reducedMotion);
  const onCameraRef = useRef(options.onCamera);
  const retryRef = useRef(options.retry);
  const scheduleRef = useRef<() => void>(() => undefined);
  const refreshRef = useRef<(zoom: number) => void>(() => undefined);
  /** Bumped on every enter and every teardown, so a late `enter3d` result never lands on a left view. */
  const enterRunRef = useRef(0);

  useLayoutEffect(() => {
    latest.current = options;
    onCameraRef.current = options.onCamera;
    retryRef.current = options.retry;
  });
  useEffect(() => {
    layersRef.current = layers;
  }, [layers]);
  useEffect(() => {
    clockRef.current = clock;
  }, [clock]);
  useEffect(() => {
    reducedRef.current = reducedMotion;
  }, [reducedMotion]);

  const applyLayers = useCallback((next: Layers3d) => {
    layersRef.current = next;
    setLayersState(next);
    apiRef.current?.setLayers(next);
  }, []);

  const setMode = useCallback((next: CameraMode) => {
    setModeState(next);
    const id = latest.current.selectedVehicle;
    if (next === 'free' || id === undefined) {
      chosenRef.current = undefined;
      apiRef.current?.setCameraMode('free');
    } else {
      chosenRef.current = id;
      apiRef.current?.setCameraMode(next, id);
    }
  }, []);

  /** The routes of the vehicles inside the viewport at zoom ≥ 14, for their shapes (E-02, DR-111). */
  const refreshViewportRoutes = useCallback(
    (zoom: number) => {
      const handle = latest.current.handle;
      if (zoom < SHAPE_MIN_ZOOM || !handle) {
        setViewportRoutes((previous) => (previous.length === 0 ? previous : NO_ROUTES));
        return;
      }
      const bounds = handle.viewBounds();
      const items = queryClient.getQueryData<WithAsOf<{ items: LiveVehicle[] }>>(
        liveVehiclesQuery(latest.current.routeIds).queryKey,
      )?.data.items;
      const routes = new Set<string>();
      for (const vehicle of items ?? []) {
        if (vehicle.lon < bounds[0] || vehicle.lon > bounds[2]) continue;
        if (vehicle.lat < bounds[1] || vehicle.lat > bounds[3]) continue;
        routes.add(vehicle.routeId);
        if (routes.size >= MAX_SHAPE_ROUTES) break;
      }
      const next = [...routes];
      setViewportRoutes((previous) =>
        previous.length === next.length && previous.every((id, index) => id === next[index]) ? previous : next,
      );
    },
    [queryClient],
  );
  useEffect(() => {
    refreshRef.current = refreshViewportRoutes;
  }, [refreshViewportRoutes]);

  // Enter on `view=3d` and leave (2D, unmount) with `dispose`; `generation` re-enters after a retry.
  const hasHandle = options.handle !== undefined;
  useEffect(() => {
    if (!options.active || !hasHandle) return;
    // A run counter, bumped again in the cleanup: a late enter from a torn-down effect is dropped, not applied.
    const run = (enterRunRef.current += 1);
    void (async () => {
      setPhase('loading');
      try {
        const initial = await readInitialLayers();
        if (enterRunRef.current !== run) return;
        applyLayers(initial);
        const target = latest.current.handle;
        if (!target) return;
        const events: Map3dEvents = {
          onSlow: () => {
            notify.warning(copy.slow.message, {
              action: {
                label: copy.slow.turnBackOn,
                onClick: () => {
                  applyLayers({ ...layersRef.current, buildings: true });
                },
              },
            });
          },
          onCameraMode: (next) => {
            setModeState(next);
            if (next === 'free') chosenRef.current = undefined;
          },
          onMoveEnd: (camera) => {
            onCameraRef.current(camera);
            refreshRef.current(camera.zoom);
          },
        };
        const entered = await target.enter3d(
          {
            ...initial,
            reducedMotion: reducedRef.current,
            pitch: latest.current.pitch,
            clock: () => clockRef.current.now(),
          },
          events,
        );
        if (enterRunRef.current !== run) {
          entered.dispose();
          return;
        }
        apiRef.current = entered;
        setApi(entered);
        setPhase('ready');
      } catch {
        if (enterRunRef.current !== run) return;
        setPhase('failed');
        latest.current.onLoadError();
        notify.error(copy.failed, {
          action: {
            label: copy.retry,
            onClick: () => {
              retryRef.current();
            },
          },
        });
      }
    })();
    return () => {
      enterRunRef.current += 1;
      apiRef.current?.dispose();
      apiRef.current = undefined;
      setApi(undefined);
    };
  }, [options.active, hasHandle, generation, applyLayers]);

  const state: Map3dState = options.active ? phase : 'idle';
  const isReady = state === 'ready';

  // Vehicles straight from the query cache into the controller, one `setVehicles` per animation frame at most.
  const routeKey = options.routeIds.join(',');
  useEffect(() => {
    if (!isReady) return;
    const observer = new QueryObserver(queryClient, liveVehiclesQuery(routeKey ? routeKey.split(',') : []));
    let frame = 0;
    const draw = () => {
      frame = 0;
      const current = latest.current;
      const now = clockRef.current.now();
      const items = visibleVehicles(observer.getCurrentResult().data?.data.items ?? [], now);
      const fixes: FixInput[] = [];
      const vehicles: Vehicle3d[] = [];
      for (const item of items) {
        const route = current.routes.get(item.routeId);
        fixes.push({
          vehicleId: item.vehicleId,
          tripId: item.tripId,
          routeId: item.routeId,
          directionId: item.directionId,
          lon: item.lon,
          lat: item.lat,
          speedMps: item.speedMps,
          bearing: item.bearing,
          currentStatus: item.currentStatus,
          at: toMillis(item.eventTimestamp),
        });
        vehicles.push({
          vehicleId: item.vehicleId,
          label: routeName(item.routeId, current.routes),
          colour: vehicleColour(item, current.mode, current.palette, current.routes),
          train: route !== undefined && route.routeType <= 2,
          opacity: isFaded(item, now)
            ? 0.4
            : current.focus !== undefined && !current.focus.has(item.vehicleId)
              ? 0.5
              : 1,
          selected: item.vehicleId === current.selectedVehicle,
        });
      }
      apiRef.current?.setVehicles(fixes, vehicles);
    };
    const request = () => {
      frame ||= requestAnimationFrame(draw);
    };
    scheduleRef.current = request;
    const unsubscribe = observer.subscribe(request);
    request();
    const timer = setInterval(request, AGE_REDRAW_MS);
    return () => {
      unsubscribe();
      clearInterval(timer);
      cancelAnimationFrame(frame);
      scheduleRef.current = () => undefined;
    };
  }, [isReady, routeKey, queryClient]);

  // A new colour mode, palette, selection or focus redraws at once.
  useEffect(() => {
    scheduleRef.current();
  }, [options.mode, options.palette, options.routes, options.selectedVehicle, options.focus]);

  // Shapes of the selected routes, plus the routes of the on-screen vehicles at zoom ≥ 14.
  const shapeRouteIds = useMemo(
    () => [...new Set([...options.routeIds, ...viewportRoutes])],
    [options.routeIds, viewportRoutes],
  );
  const details = useQueries({
    queries: shapeRouteIds.map((routeId) => routeDetailQuery(routeId)),
    combine: combineDetails,
  });
  useEffect(() => {
    if (!isReady) return;
    apiRef.current?.setShapes(details.map(toRouteShapes));
  }, [isReady, details]);

  // A new selection starts in Free unless it is the vehicle the user explicitly follows (screens/live-map §6).
  useEffect(() => {
    if (!isReady) return;
    const id = options.selectedVehicle;
    if (id !== undefined && chosenRef.current === id) return;
    chosenRef.current = undefined;
    setModeState('free');
    apiRef.current?.setCameraMode('free');
  }, [isReady, options.selectedVehicle]);

  useEffect(() => {
    apiRef.current?.setReducedMotion(reducedMotion);
  }, [isReady, reducedMotion]);

  useEffect(() => {
    if (!isReady || options.selectedVehicle === undefined) return;
    const id = options.selectedVehicle;
    const tick = () => {
      setEstimateAge(apiRef.current?.estimate(id)?.fixAgeSeconds);
    };
    const timer = setInterval(tick, ESTIMATE_TICK_MS);
    tick();
    return () => {
      clearInterval(timer);
    };
  }, [isReady, options.selectedVehicle]);

  const retry = useCallback(() => {
    latest.current.retry();
    setGeneration((value) => value + 1);
  }, []);

  const selectionAge = options.selectedVehicle !== undefined ? estimateAge : undefined;
  return {
    state,
    mode,
    setMode,
    layers,
    setLayers: applyLayers,
    estimateAge: isReady ? selectionAge : undefined,
    api,
    retry,
  };
}
