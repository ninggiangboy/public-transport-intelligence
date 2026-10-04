import { QueryObserver, useQueryClient } from '@tanstack/react-query';
import type { FeatureCollection } from 'geojson';
import { useEffect, useLayoutEffect, useRef } from 'react';

import type { MapHandle } from '@/features/map/handle';
import {
  bunchingFeatures,
  pointFeatures,
  vehicleFeatures,
  visibleVehicles,
  type LiveVehicle,
  type Palette,
  type RouteItem,
} from '@/features/map/model';
import { liveVehiclesQuery } from '@/features/map/queries';
import type { ColourMode } from '@/features/map/search';
import { useBusinessClock } from '@/lib/business-clock';

// Vehicle positions straight from the query cache into MapLibre (DOC-34 §7, DOC-35 §6.2): every change of the
// snapshot (a `vehicles.batch`, a poll, a refetch) schedules one `setData` on the next animation frame, without a React
// render. Opacity follows the age of each fix, so the layer is also redrawn every few seconds without new data.

const AGE_REDRAW_MS = 5_000;
const EMPTY: FeatureCollection = { type: 'FeatureCollection', features: [] };

export interface VehicleLayerOptions {
  mode: ColourMode;
  palette: Palette;
  routes: ReadonlyMap<string, RouteItem>;
  /** Viewer data: halos and links of bunching pairs. */
  showBunching: boolean;
  selectedVehicle?: string;
  /** The vehicles of a selected bunching pair; the others are dimmed. */
  focus?: ReadonlySet<string>;
  /** Called with the selected vehicle each time it is drawn, for "Follow". */
  onSelectedMoved?: (vehicle: LiveVehicle) => void;
}

export function useVehicleLayers(
  handle: MapHandle | undefined,
  routeIds: readonly string[],
  options: VehicleLayerOptions,
) {
  const queryClient = useQueryClient();
  const clock = useBusinessClock();
  const latest = useRef(options);
  const schedule = useRef<() => void>(() => undefined);
  const routeKey = routeIds.join(',');

  useLayoutEffect(() => {
    latest.current = options;
  });

  useEffect(() => {
    if (!handle) return;
    const observer = new QueryObserver(queryClient, liveVehiclesQuery(routeKey ? routeKey.split(',') : []));
    let frame = 0;
    let lastSelected: LiveVehicle | undefined;
    const draw = () => {
      frame = 0;
      const current = latest.current;
      const items = observer.getCurrentResult().data?.data.items ?? [];
      const vehicles = visibleVehicles(items, clock.now());
      handle.set(
        'vehicles',
        vehicleFeatures(vehicles, {
          mode: current.mode,
          palette: current.palette,
          routes: current.routes,
          now: clock.now(),
          ...(current.focus ? { focus: current.focus } : {}),
        }),
      );
      handle.set('bunching', current.showBunching ? bunchingFeatures(vehicles) : EMPTY);
      const selected = current.selectedVehicle
        ? vehicles.find((vehicle) => vehicle.vehicleId === current.selectedVehicle)
        : undefined;
      handle.set('selection', selected ? pointFeatures([selected], () => ({})) : EMPTY);
      if (selected && (selected.lat !== lastSelected?.lat || selected.lon !== lastSelected.lon)) {
        current.onSelectedMoved?.(selected);
      }
      lastSelected = selected;
    };
    const request = () => {
      frame ||= requestAnimationFrame(draw);
    };
    schedule.current = request;
    const unsubscribe = observer.subscribe(request);
    request();
    const timer = setInterval(request, AGE_REDRAW_MS);
    return () => {
      unsubscribe();
      clearInterval(timer);
      cancelAnimationFrame(frame);
      schedule.current = () => undefined;
    };
  }, [handle, queryClient, clock, routeKey]);

  // A new colour mode, selection or palette redraws at once.
  useEffect(() => {
    schedule.current();
  }, [options.mode, options.palette, options.routes, options.showBunching, options.selectedVehicle, options.focus]);
}
