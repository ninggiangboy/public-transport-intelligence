import type { FeatureCollection } from 'geojson';

import type { Bounds } from '@/features/map/model';
import type { Camera } from '@/features/map/search';
// eslint-disable-next-line import-x/no-restricted-paths -- the 3D view's type-only contract, read by the map's canvas (DR-111).
import type { Enter3dOptions, Map3dApi, Map3dEvents } from '@/features/map3d/api';

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
  /**
   * Enters the 3D view (DR-111): MapCanvas `import()`s the controller, hands it the live map and returns its API.
   * The canvas aria-label becomes "3D vehicle map" and the 2D layers are hidden by the controller.
   */
  enter3d: (options: Enter3dOptions, events: Map3dEvents) => Promise<Map3dApi>;
  /** The bounds of the current viewport, for the E-02 shapes of the vehicles on screen (DR-111). */
  viewBounds: () => Bounds;
}

/** A click on a feature of one of the interactive layers. */
export interface MapPick {
  layer: string;
  properties: Record<string, unknown>;
  lon: number;
  lat: number;
}
