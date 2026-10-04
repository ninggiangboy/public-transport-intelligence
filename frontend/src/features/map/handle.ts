import type { FeatureCollection } from 'geojson';

import type { Bounds } from '@/features/map/model';
import type { Camera } from '@/features/map/search';

// What the screen can do with the map, without importing MapLibre: MapCanvas implements it, and component tests
// (jsdom has no WebGL) replace MapCanvas with a double that records the calls.

export type SourceId = 'routes' | 'disruption-stops' | 'stops' | 'vehicles' | 'bunching' | 'selection';

export interface MapHandle {
  /** Replaces the features of a data source (DOC-35 §6.2). */
  set: (source: SourceId, collection: FeatureCollection) => void;
  /** Fits the camera on the bounds; `padding` in pixels. */
  fitBounds: (bounds: Bounds, padding?: number) => void;
  /** Centres the camera on a point, at `zoom` when given. */
  moveTo: (lon: number, lat: number, zoom?: number) => void;
  zoomBy: (delta: number) => void;
  camera: () => Camera;
  /** The zoom at which a cluster of vehicles falls apart. */
  clusterZoom: (clusterId: number) => Promise<number>;
}

/** A click on a feature of one of the interactive layers. */
export interface MapPick {
  layer: string;
  properties: Record<string, unknown>;
  lon: number;
  lat: number;
}
