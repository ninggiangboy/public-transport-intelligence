import type { FeatureCollection } from 'geojson';
import { useEffect, type ReactNode } from 'react';
import { vi } from 'vitest';

import type { MapHandle, MapPick, SourceId } from '@/features/map/handle';
import type { Bounds } from '@/features/map/model';
import type { Camera } from '@/features/map/search';
import { mapCopy } from '@/i18n/map';

// jsdom has no WebGL, so component tests run the live map on this double of MapCanvas (setup.ts): it hands the screen
// a MapHandle that records what the screen draws and where it moves the camera, and lets a test click on features.

interface MapDouble {
  /** The last features given to each source. */
  data: Partial<Record<SourceId, FeatureCollection>>;
  fitBounds: ReturnType<typeof vi.fn<(bounds: Bounds, padding?: number) => void>>;
  moveTo: ReturnType<typeof vi.fn<(lon: number, lat: number, zoom?: number) => void>>;
  zoomBy: ReturnType<typeof vi.fn<(delta: number) => void>>;
  /** What MapCanvas got from the screen, to play user actions on the map. */
  pick?: (pick: MapPick | undefined) => void;
  moveEnd?: (camera: Camera) => void;
  userMove?: () => void;
  initialCamera?: string;
}

export const mapDouble: MapDouble = {
  data: {},
  fitBounds: vi.fn(),
  moveTo: vi.fn(),
  zoomBy: vi.fn(),
};

export function resetMapDouble() {
  mapDouble.data = {};
  mapDouble.fitBounds.mockReset();
  mapDouble.moveTo.mockReset();
  mapDouble.zoomBy.mockReset();
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
