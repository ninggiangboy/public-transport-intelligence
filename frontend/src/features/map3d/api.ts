import type { FixInput } from '@/features/map3d/motion';

// The contract between the live map screen and the 3D view (DR-111, ADR-0033). This file only declares types, so the
// screen can import it without pulling the lazily loaded 3D code (`controller.ts`, `layer.ts`) into the `/map` chunk.

export type CameraMode = 'free' | 'follow' | 'chase';

/** What the layer needs to draw one vehicle besides its position. */
export interface Vehicle3d {
  vehicleId: string;
  /** The route name on the label disc. */
  label: string;
  /** `#rrggbb`, from "Colour vehicles by". */
  colour: string;
  /** Route types 0, 1 and 2 (tram, subway, rail) are drawn as a two-car train. */
  train: boolean;
  /** 0.4 when the fix is stale, 0.5 when the vehicle is dimmed by a bunching focus, else 1. */
  opacity: number;
  selected: boolean;
}

/** Shapes of one route, one polyline per direction, as [lon, lat]. */
export interface RouteShapes {
  routeId: string;
  directions: readonly { directionId: number; coordinates: readonly (readonly [number, number])[] }[];
}

export interface Layers3d {
  /** `fill-extrusion` of the `buildings` layer (zoom 13 and up). */
  buildings: boolean;
  /** A dashed ring and a dot at the last real GPS fix of every vehicle. */
  lastFix: boolean;
}

export interface Map3dEvents {
  /** Under 30 fps for 5 s in a row: the controller has already turned the buildings off (DR-111). */
  onSlow: () => void;
  /** A drag by the user ends Follow and Chase. */
  onCameraMode: (mode: CameraMode) => void;
  /** After every camera move; the screen writes `c` from it. */
  onMoveEnd: (camera: { lon: number; lat: number; zoom: number; bearing: number; pitch: number }) => void;
}

export interface Enter3dOptions extends Layers3d {
  reducedMotion: boolean;
  /** Pitch to tilt to when entering; `c` of the URL wins when it carries one. */
  pitch: number;
  /** The business clock; fix times and "now" must be in the same time base. */
  clock: () => number;
}

export interface Map3dApi {
  /** One call per vehicles snapshot: the fixes for the motion model and the drawing data. */
  setVehicles: (fixes: readonly FixInput[], vehicles: readonly Vehicle3d[]) => void;
  setShapes: (routes: readonly RouteShapes[]) => void;
  setLayers: (layers: Layers3d) => void;
  setReducedMotion: (value: boolean) => void;
  /** `vehicleId` is required for Follow and Chase. */
  setCameraMode: (mode: CameraMode, vehicleId?: string) => void;
  /** Degrees; positive tilts towards the horizon. Clamped to 0–70. */
  tilt: (degrees: number) => void;
  /** Degrees clockwise. */
  rotate: (degrees: number) => void;
  resetNorth: () => void;
  /** The vehicle under a pointer position in CSS pixels, if any. */
  pick: (x: number, y: number) => string | undefined;
  /** For the vehicle panel: fix age and whether the drawn position is an estimate. */
  estimate: (vehicleId: string) => { fixAgeSeconds: number; estimated: boolean } | undefined;
  /** Screen position of a vehicle in CSS pixels (E2E-MAP-06). */
  screenPosition: (vehicleId: string) => { x: number; y: number } | undefined;
  /** Leaves 3D: removes the layers, restores pitch 0 and the 2D interaction. */
  dispose: () => void;
}
