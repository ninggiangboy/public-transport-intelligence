import type { FeatureCollection } from 'geojson';
import { useEffect, type ReactNode } from 'react';
import { vi } from 'vitest';

import type { MapHandle, MapPick, SourceId } from '@/features/map/handle';
import type { Bounds } from '@/features/map/model';
import type { Camera } from '@/features/map/search';
import type { CameraMode, Enter3dOptions, Map3dApi, Map3dEvents } from '@/features/map3d/api';
import { mapCopy } from '@/i18n/map';

// jsdom has no WebGL, so component tests run the live map on this double of MapCanvas (setup.ts): it hands the screen
// a MapHandle that records what the screen draws and where it moves the camera, and lets a test click on features. It
// also answers `enter3d` with a recording Map3dApi, so the 3D view can be driven from a test.

type ApiMock<K extends keyof Map3dApi> = ReturnType<typeof vi.fn<Map3dApi[K]>>;
type MockFn = ReturnType<typeof vi.fn>;

/** The recording 3D controller: `emit3d` plays its events, `api` records the calls the screen makes. */
export interface Map3dDouble {
  /** The options and events of the last successful `enter3d`. */
  options?: Enter3dOptions;
  events?: Map3dEvents;
  api: {
    setVehicles: ApiMock<'setVehicles'>;
    setShapes: ApiMock<'setShapes'>;
    setLayers: ApiMock<'setLayers'>;
    setReducedMotion: ApiMock<'setReducedMotion'>;
    setCameraMode: ApiMock<'setCameraMode'>;
    tilt: ApiMock<'tilt'>;
    rotate: ApiMock<'rotate'>;
    resetNorth: ApiMock<'resetNorth'>;
    pick: ApiMock<'pick'>;
    estimate: ApiMock<'estimate'>;
    screenPosition: ApiMock<'screenPosition'>;
    dispose: ApiMock<'dispose'>;
  };
}

export const map3dDouble: Map3dDouble = {
  api: {
    setVehicles: vi.fn<Map3dApi['setVehicles']>(),
    setShapes: vi.fn<Map3dApi['setShapes']>(),
    setLayers: vi.fn<Map3dApi['setLayers']>(),
    setReducedMotion: vi.fn<Map3dApi['setReducedMotion']>(),
    setCameraMode: vi.fn<Map3dApi['setCameraMode']>(),
    tilt: vi.fn<Map3dApi['tilt']>(),
    rotate: vi.fn<Map3dApi['rotate']>(),
    resetNorth: vi.fn<Map3dApi['resetNorth']>(),
    pick: vi.fn<Map3dApi['pick']>(() => undefined),
    estimate: vi.fn<Map3dApi['estimate']>(() => undefined),
    screenPosition: vi.fn<Map3dApi['screenPosition']>(() => undefined),
    dispose: vi.fn<Map3dApi['dispose']>(),
  },
};

/** Plays the controller's own events (a slow frame, a camera-mode change, a camera move). */
export const emit3d = {
  slow: () => {
    map3dDouble.events?.onSlow();
  },
  cameraMode: (mode: CameraMode) => {
    map3dDouble.events?.onCameraMode(mode);
  },
  moveEnd: (camera: { lon: number; lat: number; zoom: number; bearing: number; pitch: number }) => {
    map3dDouble.events?.onMoveEnd(camera);
  },
};

interface MapDouble {
  /** The last features given to each source. */
  data: Partial<Record<SourceId, FeatureCollection>>;
  fitBounds: ReturnType<typeof vi.fn<(bounds: Bounds, padding?: number) => void>>;
  moveTo: ReturnType<typeof vi.fn<(lon: number, lat: number, zoom?: number) => void>>;
  zoomBy: ReturnType<typeof vi.fn<(delta: number) => void>>;
  /** Snapshot of the 3D view: `enter3d` records its calls here and answers with the recording API. */
  enter3d: ReturnType<typeof vi.fn<(options: Enter3dOptions, events: Map3dEvents) => Promise<Map3dApi>>>;
  viewBounds: ReturnType<typeof vi.fn<() => Bounds>>;
  /** When true, `enter3d` rejects as if the 3D chunk failed to load. */
  reject3d: boolean;
  /** What MapCanvas got from the screen, to play user actions on the map. */
  pick?: (pick: MapPick | undefined) => void;
  moveEnd?: (camera: Camera) => void;
  userMove?: () => void;
  initialCamera?: string;
}

const DEFAULT_BOUNDS: Bounds = [-93.3, 44.9, -93.2, 45.0];

export const mapDouble: MapDouble = {
  data: {},
  fitBounds: vi.fn(),
  moveTo: vi.fn(),
  zoomBy: vi.fn(),
  enter3d: vi.fn<(options: Enter3dOptions, events: Map3dEvents) => Promise<Map3dApi>>((options, events) => {
    if (mapDouble.reject3d) return Promise.reject(new Error('3D chunk failed to load'));
    map3dDouble.options = options;
    map3dDouble.events = events;
    return Promise.resolve(map3dDouble.api);
  }),
  viewBounds: vi.fn<() => Bounds>(() => DEFAULT_BOUNDS),
  reject3d: false,
};

export function resetMapDouble() {
  mapDouble.data = {};
  mapDouble.fitBounds.mockReset();
  mapDouble.moveTo.mockReset();
  mapDouble.zoomBy.mockReset();
  // `mockClear` rather than `mockReset`: the fake `enter3d` keeps its implementation across tests.
  mapDouble.enter3d.mockClear();
  mapDouble.viewBounds.mockClear();
  mapDouble.reject3d = false;
  for (const fn of Object.values(map3dDouble.api) as MockFn[]) fn.mockReset();
  delete map3dDouble.options;
  delete map3dDouble.events;
  delete mapDouble.pick;
  delete mapDouble.moveEnd;
  delete mapDouble.userMove;
  delete mapDouble.initialCamera;
}

/** Features of a source by a property, for assertions: `featureIds('vehicles')`. */
export function featureIds(source: SourceId, property = 'id'): unknown[] {
  return (mapDouble.data[source]?.features ?? []).map((feature) => feature.properties?.[property] as unknown);
}

const handle: MapHandle = {
  set: (source, collection) => {
    mapDouble.data[source] = collection;
  },
  fitBounds: (bounds, padding) => {
    mapDouble.fitBounds(bounds, padding);
  },
  moveTo: (lon, lat, zoom) => {
    mapDouble.moveTo(lon, lat, zoom);
  },
  zoomBy: (delta) => {
    mapDouble.zoomBy(delta);
  },
  camera: () => ({ lon: -93.265, lat: 44.9778, zoom: 12 }),
  clusterZoom: () => Promise.resolve(13),
  enter3d: (options, events) => mapDouble.enter3d(options, events),
  viewBounds: () => mapDouble.viewBounds(),
};

interface CanvasProps {
  initialCamera?: string;
  onReady: (handle: MapHandle | undefined) => void;
  onMoveEnd: (camera: Camera) => void;
  onUserMove: () => void;
  onPick: (pick: MapPick | undefined) => void;
  children?: ReactNode;
}

export function MapCanvas({ initialCamera, onReady, onMoveEnd, onUserMove, onPick, children }: CanvasProps) {
  useEffect(() => {
    Object.assign(mapDouble, { pick: onPick, moveEnd: onMoveEnd, userMove: onUserMove });
    if (initialCamera !== undefined) Object.assign(mapDouble, { initialCamera });
  });
  useEffect(() => {
    onReady(handle);
    return () => {
      onReady(undefined);
    };
  }, [onReady]);
  return (
    <div role="region" aria-label={mapCopy.map.canvasLabel}>
      {children}
    </div>
  );
}

export function MapPing() {
  return null;
}

export function MapPopup({ children }: { children: ReactNode }) {
  return <div role="dialog">{children}</div>;
}
