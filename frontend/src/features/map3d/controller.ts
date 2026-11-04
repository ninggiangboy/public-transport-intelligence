// The lazily loaded 3D controller of the live map (P5-18, DR-111, ADR-0033). The screen `import()`s this
// module the first time it enters `view=3d`, so it owns everything 3D and shares only `api.ts` with the 2D
// chunk. It waits for the base style's own buildings and names the 2D layers of MapScene by hand: features
// never import each other (DOC-34 §9.1), and this file stays under the 3D chunk budget.

import type { Feature, FeatureCollection, Point } from 'geojson';
import type { GeoJSONSource, LayerSpecification, Map as MapLibreMap } from 'maplibre-gl';

import type {
  CameraMode,
  Enter3dOptions,
  Layers3d,
  Map3dApi,
  Map3dEvents,
  RouteShapes,
  Vehicle3d,
} from '@/features/map3d/api';
import { VehicleLayer } from '@/features/map3d/layer';
import { MotionModel, indexShape } from '@/features/map3d/motion';
import type { FixInput, ShapeIndex, Shapes } from '@/features/map3d/motion';

// The buildings layer rides the base style the map already carries (baseStyle.ts): offline Protomaps tiles, or
// the online OpenFreeMap style used while developing. Both source and source-layer names are the style's, not
// ours; `height` / `min_height` missing on 92 % of the tiles is why every expression coalesces to a default.
const BUILDINGS_LAYER = 'buildings-3d';
const FIX_SOURCE = 'fix-3d';
const FIX_RING = 'fix-3d-ring';
const FIX_DOT = 'fix-3d-dot';

// Every layer of MapScene that draws the 2D `vehicles` source: the clusters, their count, the very-late ring,
// the arrows/dots and the route labels. The 3D vehicle layer replaces all of them; `selection` and `bunching`
// keep drawing as they are.
const HIDDEN_2D_LAYERS = [
  'vehicle-clusters',
  'vehicle-cluster-count',
  'vehicles-very-late',
  'vehicles',
  'vehicle-labels',
];

// DR-111: tilt 0–70°, the entry tilt animates over 600 ms, the camera buttons over 300 ms. The slow-frame
// fallback watches a 5 s window (under 30 fps trims the buildings, above 45 fps re-arms the toast).
const MAX_PITCH = 70;
const ENTER_DURATION_MS = 600;
const MOVE_DURATION_MS = 300;
const SLOW_FPS = 30;
const FAST_FPS = 45;
const FPS_WINDOW_MS = 5000;
const CHASE_LERP = 0.15;

const EMPTY_FIXES: FeatureCollection = { type: 'FeatureCollection', features: [] };

/** A colour of the theme tokens on <html> (tokens.css); the 3D chunk reads it directly instead of a palette object. */
function token(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function clampPitch(degrees: number): number {
  return Math.max(0, Math.min(MAX_PITCH, degrees));
}

export function enter3d(map: MapLibreMap, options: Enter3dOptions, events: Map3dEvents): Map3dApi {
  let buildings = options.buildings;
  let lastFix = options.lastFix;
  let reducedMotion = options.reducedMotion;
  // The fps fallback turns the buildings off on its own; only `setLayers({ buildings: true })` arms them again.
  let slowOff = false;
  let slowNotified = false;

  let cameraMode: CameraMode = 'free';
  let followed: string | undefined;
  let chaseBearing = 0;

  let fixData: FeatureCollection = EMPTY_FIXES;
  // The motion model keeps this object forever, so `setShapes` fills the map in place instead of replacing it.
  const shapesByRoute = new Map<string, ShapeIndex[]>();
  const shapes: Shapes = {
    forRoute: (routeId, directionId) => shapesByRoute.get(`${routeId}:${directionId}`) ?? [],
  };

  const model = new MotionModel({ shapes, reduced: reducedMotion });
  const layer = new VehicleLayer(model, { clock: options.clock, onFrame });

  let rafId: number | null = null;
  let windowStart: number | null = null;
  let windowFrames = 0;

  function setVisibility(id: string, visible: boolean): void {
    if (map.getLayer(id) !== undefined) {
      map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none');
    }
  }

  function applyVisibility(): void {
    setVisibility(BUILDINGS_LAYER, buildings && !slowOff);
    setVisibility(FIX_RING, lastFix);
    setVisibility(FIX_DOT, lastFix);
  }

  function hide2dVehicles(): void {
    for (const id of HIDDEN_2D_LAYERS) setVisibility(id, false);
  }

  function buildingsLayer(): LayerSpecification | null {
    const sources = map.getStyle().sources;
    const colour = token('--map-block');
    if (sources.protomaps !== undefined) {
      return {
        id: BUILDINGS_LAYER,
        type: 'fill-extrusion',
        source: 'protomaps',
        'source-layer': 'buildings',
        minzoom: 13,
        layout: { visibility: buildings && !slowOff ? 'visible' : 'none' },
        paint: {
          'fill-extrusion-color': colour,
          'fill-extrusion-height': ['coalesce', ['get', 'height'], 8],
          'fill-extrusion-base': ['coalesce', ['get', 'min_height'], 0],
          'fill-extrusion-opacity': 0.92,
        },
      };
    }
    if (sources.openmaptiles !== undefined) {
      return {
        id: BUILDINGS_LAYER,
        type: 'fill-extrusion',
        source: 'openmaptiles',
        'source-layer': 'building',
        minzoom: 13,
        layout: { visibility: buildings && !slowOff ? 'visible' : 'none' },
        paint: {
          'fill-extrusion-color': colour,
          'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 8],
          'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0],
          'fill-extrusion-opacity': 0.92,
        },
      };
    }
    return null;
  }

  function fixLayers(): LayerSpecification[] {
    // A dashed ring needs a `line` layer, which cannot follow a point; a solid `circle` stroke is what we have.
    const visibility = lastFix ? 'visible' : 'none';
    const colour = token('--map-label');
    return [
      {
        id: FIX_RING,
        type: 'circle',
        source: FIX_SOURCE,
        layout: { visibility },
        paint: {
          'circle-radius': 8,
          'circle-color': 'rgba(0,0,0,0)',
          'circle-stroke-color': colour,
          'circle-stroke-width': 1.5,
        },
      },
      {
        id: FIX_DOT,
        type: 'circle',
        source: FIX_SOURCE,
        layout: { visibility },
        paint: { 'circle-radius': 3, 'circle-color': colour },
      },
    ];
  }

  /** Adds the buildings, the fix marker and the vehicle layer to the current style. Runs again after `style.load`. */
  function install(): void {
    if (map.getSource(FIX_SOURCE) === undefined) {
      map.addSource(FIX_SOURCE, { type: 'geojson', data: fixData });
    }
    const building = buildingsLayer();
    if (building !== null && map.getLayer(BUILDINGS_LAYER) === undefined) map.addLayer(building);
    for (const spec of fixLayers()) {
      if (map.getLayer(spec.id) === undefined) map.addLayer(spec);
    }
    // The custom layer draws last, so buildings, fix markers and the 2D overlays stay underneath it.
    if (map.getLayer(layer.id) !== undefined) map.moveLayer(layer.id);
    else map.addLayer(layer);
    applyVisibility();
    hide2dVehicles();
  }

  function setVehicles(fixes: readonly FixInput[], vehicles: readonly Vehicle3d[]): void {
    model.update(fixes, options.clock());
    layer.setVehicles(vehicles);
    const features: Feature<Point>[] = [];
    for (const vehicle of vehicles) {
      const drawn = model.get(vehicle.vehicleId);
      if (drawn === undefined) continue;
      features.push({
        type: 'Feature',
        properties: {},
        geometry: { type: 'Point', coordinates: [drawn.lastFix.lon, drawn.lastFix.lat] },
      });
    }
    fixData = { type: 'FeatureCollection', features };
    void map.getSource<GeoJSONSource>(FIX_SOURCE)?.setData(fixData);
  }

  function setShapes(routes: readonly RouteShapes[]): void {
    shapesByRoute.clear();
    for (const route of routes) {
      for (const direction of route.directions) {
        // One polyline per direction; the model picks the nearest candidate within 50 m.
        shapesByRoute.set(`${route.routeId}:${direction.directionId}`, [indexShape(direction.coordinates)]);
      }
    }
  }

  function setLayers(next: Layers3d): void {
    buildings = next.buildings;
    lastFix = next.lastFix;
    if (next.buildings) {
      // An explicit "on" is also the user's re-arm after the slow-frame fallback turned them off.
      slowOff = false;
      slowNotified = false;
    }
    applyVisibility();
  }

  function setReducedMotion(value: boolean): void {
    reducedMotion = value;
    model.setReduced(value);
  }

  function setCameraMode(mode: CameraMode, vehicleId?: string): void {
    cameraMode = mode;
    followed = mode === 'free' ? undefined : vehicleId;
    chaseBearing = map.getBearing();
  }

  function cameraDuration(): number {
    return reducedMotion ? 0 : MOVE_DURATION_MS;
  }

  function tilt(degrees: number): void {
    map.easeTo({ pitch: clampPitch(degrees), duration: cameraDuration() });
  }

  function rotate(degrees: number): void {
    map.easeTo({ bearing: map.getBearing() + degrees, duration: cameraDuration() });
  }

  function resetNorth(): void {
    map.easeTo({ bearing: 0, duration: cameraDuration() });
  }

  function pick(x: number, y: number): string | undefined {
    return layer.pick(x, y);
  }

  function screenPosition(vehicleId: string): { x: number; y: number } | undefined {
    return layer.screenPosition(vehicleId);
  }

  function estimate(vehicleId: string): { fixAgeSeconds: number; estimated: boolean } | undefined {
    const drawn = model.get(vehicleId);
    return drawn === undefined ? undefined : { fixAgeSeconds: drawn.fixAgeSeconds, estimated: drawn.estimated };
  }

  /** Shortest-arc lerp towards the vehicle heading, so Chase does not snap on every jittery GPS bearing. */
  function chaseTo(heading: number): number {
    const delta = ((heading - chaseBearing + 540) % 360) - 180;
    chaseBearing = (chaseBearing + delta * CHASE_LERP + 360) % 360;
    return chaseBearing;
  }

  function onFrame(): void {
    if (cameraMode === 'free' || followed === undefined) return;
    const drawn = model.get(followed);
    if (drawn === undefined) {
      // The followed vehicle left the feed: settle on Free where the camera already is (DR-111).
      cameraMode = 'free';
      followed = undefined;
      events.onCameraMode('free');
      return;
    }
    const center: [number, number] = [drawn.lon, drawn.lat];
    if (cameraMode === 'chase') map.jumpTo({ center, bearing: chaseTo(drawn.heading) });
    else map.jumpTo({ center });
  }

  function onMoveEnd(): void {
    const center = map.getCenter();
    events.onMoveEnd({
      lon: center.lng,
      lat: center.lat,
      zoom: map.getZoom(),
      bearing: map.getBearing(),
      pitch: map.getPitch(),
    });
  }

  /** Only a real user gesture carries an `originalEvent`; a programmatic `jumpTo` does not, so Follow survives it. */
  function onUserCameraStart(event: { originalEvent?: unknown }): void {
    if (event.originalEvent === undefined) return;
    if (cameraMode === 'free') return;
    cameraMode = 'free';
    followed = undefined;
    events.onCameraMode('free');
  }

  function frame(now: number): void {
    rafId = requestAnimationFrame(frame);
    if (windowStart === null) {
      windowStart = now;
      windowFrames = 0;
    }
    windowFrames += 1;
    const span = now - windowStart;
    if (span < FPS_WINDOW_MS) return;
    const fps = (windowFrames * 1000) / span;
    if (fps < SLOW_FPS) {
      if (!slowOff) {
        slowOff = true;
        applyVisibility();
      }
      if (!slowNotified) {
        slowNotified = true;
        events.onSlow();
      }
    } else if (fps > FAST_FPS) {
      slowNotified = false;
    }
    windowStart = now;
    windowFrames = 0;
  }

  function startMonitor(): void {
    if (rafId !== null) return;
    windowStart = null;
    windowFrames = 0;
    rafId = requestAnimationFrame(frame);
  }

  function stopMonitor(): void {
    if (rafId === null) return;
    cancelAnimationFrame(rafId);
    rafId = null;
  }

  function onVisibility(): void {
    if (document.hidden) {
      layer.setPaused(true);
      stopMonitor();
      return;
    }
    layer.setPaused(false);
    map.triggerRepaint();
    startMonitor();
  }

  function dispose(): void {
    stopMonitor();
    document.removeEventListener('visibilitychange', onVisibility);
    map.off('style.load', install);
    map.off('moveend', onMoveEnd);
    map.off('dragstart', onUserCameraStart);
    map.off('rotatestart', onUserCameraStart);
    map.off('pitchstart', onUserCameraStart);
    for (const id of [BUILDINGS_LAYER, FIX_RING, FIX_DOT, layer.id]) {
      if (map.getLayer(id) !== undefined) map.removeLayer(id);
    }
    if (map.getSource(FIX_SOURCE) !== undefined) map.removeSource(FIX_SOURCE);
    for (const id of HIDDEN_2D_LAYERS) setVisibility(id, true);
    // The 2D map has `dragRotate=false` and `pitchWithRotate=false` (MapCanvas.tsx); restore that interaction.
    map.dragRotate.disable();
    map.setMaxPitch(0);
    map.jumpTo({ pitch: 0, bearing: 0 });
  }

  map.dragRotate.enable();
  map.setMaxPitch(MAX_PITCH);
  map.on('style.load', install);
  map.on('moveend', onMoveEnd);
  map.on('dragstart', onUserCameraStart);
  map.on('rotatestart', onUserCameraStart);
  map.on('pitchstart', onUserCameraStart);
  document.addEventListener('visibilitychange', onVisibility);
  install();
  map.easeTo({ pitch: clampPitch(options.pitch), duration: reducedMotion ? 0 : ENTER_DURATION_MS });
  startMonitor();

  return {
    setVehicles,
    setShapes,
    setLayers,
    setReducedMotion,
    setCameraMode,
    tilt,
    rotate,
    resetNorth,
    pick,
    estimate,
    screenPosition,
    dispose,
  };
}
