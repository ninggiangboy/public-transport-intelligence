// Controller tests (P5-18, slice B3). The custom layer needs WebGL, so it is stubbed; everything else is real:
// the MotionModel, the shapes and the camera maths. `requestAnimationFrame` is driven by hand, so a slow-frame
// episode is a loop over timestamps rather than a real wait.

import type { Map as MapLibreMap } from 'maplibre-gl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';

import type { Enter3dOptions, Map3dApi, Map3dEvents, Vehicle3d } from '@/features/map3d/api';
import { enter3d } from '@/features/map3d/controller';
import type { FixInput, MotionModel } from '@/features/map3d/motion';

interface LayerMock {
  id: string;
  model: unknown;
  options: {
    clock: () => number;
    onFrame?: ((drawn: readonly unknown[], now: number) => void) | undefined;
  };
  setVehicles: Mock;
  setPaused: Mock;
  screenPosition: Mock;
  pick: Mock;
}

const mocks = vi.hoisted(() => ({ instances: [] as unknown[] }));

vi.mock('@/features/map3d/layer', () => {
  class VehicleLayer {
    readonly id = 'vehicles-3d';
    readonly model: unknown;
    readonly options: LayerMock['options'];
    readonly setVehicles = vi.fn();
    readonly setPaused = vi.fn();
    readonly screenPosition = vi.fn();
    readonly pick = vi.fn();

    constructor(model: unknown, options: LayerMock['options']) {
      this.model = model;
      this.options = options;
      mocks.instances.push(this);
    }
  }
  return { VehicleLayer };
});

/** The most recently constructed layer stub (one `enter3d` per test). */
function layer(): LayerMock {
  const instance = mocks.instances.at(-1);
  if (instance === undefined) throw new Error('no VehicleLayer was created');
  return instance as LayerMock;
}

function createMap(styleSources: Record<string, unknown> = { protomaps: {} }) {
  const layers = new Set<string>();
  const sourceMap = new Map<string, { setData: Mock }>();
  const handlers = new Map<string, Set<(event: unknown) => void>>();
  return {
    dragRotate: { enable: vi.fn(), disable: vi.fn() },
    setMaxPitch: vi.fn(),
    easeTo: vi.fn(),
    jumpTo: vi.fn(),
    addSource: vi.fn((id: string) => {
      sourceMap.set(id, { setData: vi.fn() });
    }),
    removeSource: vi.fn((id: string) => {
      sourceMap.delete(id);
    }),
    addLayer: vi.fn((added: { id: string }) => {
      layers.add(added.id);
    }),
    removeLayer: vi.fn((id: string) => {
      layers.delete(id);
    }),
    moveLayer: vi.fn(),
    getLayer: vi.fn((id: string) => (layers.has(id) ? { id } : undefined)),
    getSource: vi.fn((id: string) => sourceMap.get(id)),
    setLayoutProperty: vi.fn(),
    getStyle: vi.fn(() => ({ sources: styleSources })),
    on: vi.fn((type: string, handler: (event: unknown) => void) => {
      const set = handlers.get(type) ?? new Set<(event: unknown) => void>();
      set.add(handler);
      handlers.set(type, set);
    }),
    off: vi.fn((type: string, handler: (event: unknown) => void) => {
      handlers.get(type)?.delete(handler);
    }),
    getCenter: vi.fn(() => ({ lng: -93.2, lat: 44.98 })),
    getZoom: vi.fn(() => 14),
    getBearing: vi.fn(() => 0),
    getPitch: vi.fn(() => 0),
    triggerRepaint: vi.fn(),
    emit(type: string, event: unknown) {
      for (const handler of handlers.get(type) ?? []) handler(event);
    },
  };
}

type FakeMap = ReturnType<typeof createMap>;

function options(overrides: Partial<Enter3dOptions> = {}): Enter3dOptions {
  return { buildings: true, lastFix: false, reducedMotion: false, pitch: 58, clock: () => 0, ...overrides };
}

function events() {
  return { onSlow: vi.fn(), onCameraMode: vi.fn(), onMoveEnd: vi.fn() } satisfies Map3dEvents;
}

const VEHICLE: Vehicle3d = {
  vehicleId: 'v1',
  label: '18',
  colour: 'rgb(51 102 204)',
  train: false,
  opacity: 1,
  selected: false,
};

function fix(overrides: Partial<FixInput> = {}): FixInput {
  return {
    vehicleId: 'v1',
    tripId: 't1',
    routeId: '18',
    directionId: 0,
    lon: -93.2,
    lat: 44.98,
    currentStatus: 'IN_TRANSIT_TO',
    at: 0,
    speedMps: 0,
    ...overrides,
  };
}

let created: Map3dApi[] = [];
let frames: FrameRequestCallback[] = [];
let nextHandle = 0;

function enter(map: FakeMap, opts: Enter3dOptions, ev: Map3dEvents): Map3dApi {
  const api = enter3d(map as unknown as MapLibreMap, opts, ev);
  created.push(api);
  return api;
}

/** Runs the callbacks that are pending at this timestamp; the monitor reschedules from inside them. */
function runFrame(now: number): void {
  const pending = frames;
  frames = [];
  for (const callback of pending) callback(now);
}

function setHidden(value: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => value });
}

beforeEach(() => {
  vi.useFakeTimers();
  mocks.instances.length = 0;
  created = [];
  frames = [];
  nextHandle = 0;
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.push(callback);
    nextHandle += 1;
    return nextHandle;
  });
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
});

afterEach(() => {
  for (const api of created) api.dispose();
  created = [];
  Object.defineProperty(document, 'hidden', { configurable: true, value: false });
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('enter3d — entering 3D', () => {
  it('enables rotation, raises the pitch limit and tilts to the given pitch', () => {
    const map = createMap();
    enter(map, options(), events());

    expect(map.dragRotate.enable).toHaveBeenCalled();
    expect(map.setMaxPitch).toHaveBeenCalledWith(70);
    expect(map.easeTo).toHaveBeenCalledWith({ pitch: 58, duration: 600 });
  });

  it('cuts instead of flying when reduced motion is on', () => {
    const map = createMap();
    enter(map, options({ reducedMotion: true }), events());

    expect(map.easeTo).toHaveBeenCalledWith({ pitch: 58, duration: 0 });
  });

  it('adds the buildings layer for the Protomaps style and the 2D layers it hides', () => {
    const map = createMap();
    map.addLayer({ id: 'vehicles' });
    enter(map, options(), events());

    expect(map.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: 'buildings-3d', type: 'fill-extrusion' }));
    expect(map.setLayoutProperty).toHaveBeenCalledWith('vehicles', 'visibility', 'none');
    expect(map.setLayoutProperty).toHaveBeenCalledWith('buildings-3d', 'visibility', 'visible');
  });

  it('uses the OpenFreeMap source when the style carries it', () => {
    const map = createMap({ openmaptiles: {} });
    enter(map, options(), events());

    expect(map.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'buildings-3d', source: 'openmaptiles', 'source-layer': 'building' }),
    );
  });
});

describe('enter3d — camera', () => {
  it('keeps the followed vehicle at the centre on every frame', () => {
    const map = createMap();
    const api = enter(map, options(), events());
    api.setVehicles([fix()], [VEHICLE]);
    api.setCameraMode('follow', 'v1');

    layer().options.onFrame?.([], 1000);

    expect(map.jumpTo).toHaveBeenCalledWith({ center: [-93.2, 44.98] });
  });

  it('leaves Follow only on a user gesture, which carries an originalEvent', () => {
    const map = createMap();
    const ev = events();
    const api = enter(map, options(), ev);
    api.setCameraMode('follow', 'v1');

    map.emit('dragstart', {});
    expect(ev.onCameraMode).not.toHaveBeenCalled();

    map.emit('dragstart', { originalEvent: {} });
    expect(ev.onCameraMode).toHaveBeenCalledWith('free');
  });

  it('clamps tilt to 0–70 degrees', () => {
    const map = createMap();
    const api = enter(map, options(), events());

    api.tilt(120);
    expect(map.easeTo).toHaveBeenLastCalledWith({ pitch: 70, duration: 300 });

    api.tilt(-5);
    expect(map.easeTo).toHaveBeenLastCalledWith({ pitch: 0, duration: 300 });
  });

  it('reports the view after every moveend', () => {
    const map = createMap();
    const ev = events();
    enter(map, options(), ev);

    map.emit('moveend', {});

    expect(ev.onMoveEnd).toHaveBeenCalledWith({ lon: -93.2, lat: 44.98, zoom: 14, bearing: 0, pitch: 0 });
  });
});

describe('enter3d — fallbacks', () => {
  it('turns the buildings off and warns once after 5 s under 30 fps', () => {
    const map = createMap();
    const ev = events();
    enter(map, options(), ev);

    for (let now = 0; now <= 5000; now += 100) runFrame(now);
    expect(map.setLayoutProperty).toHaveBeenCalledWith('buildings-3d', 'visibility', 'none');
    expect(ev.onSlow).toHaveBeenCalledTimes(1);

    for (let now = 5100; now <= 10000; now += 100) runFrame(now);
    expect(ev.onSlow).toHaveBeenCalledTimes(1);
  });

  it('does not trim the buildings while the frame rate is healthy', () => {
    const map = createMap();
    const ev = events();
    enter(map, options(), ev);

    for (let now = 0; now <= 5000; now += 16) runFrame(now);

    expect(ev.onSlow).not.toHaveBeenCalled();
    expect(map.setLayoutProperty).not.toHaveBeenCalledWith('buildings-3d', 'visibility', 'none');
  });

  it('pauses the layer while the tab is hidden and resumes on return', () => {
    const map = createMap();
    enter(map, options(), events());

    setHidden(true);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(layer().setPaused).toHaveBeenCalledWith(true);

    setHidden(false);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(layer().setPaused).toHaveBeenCalledWith(false);
    expect(map.triggerRepaint).toHaveBeenCalled();
  });
});

describe('enter3d — data and teardown', () => {
  it('indexes shapes so the model projects a fix back onto the polyline', () => {
    const map = createMap();
    const api = enter(map, options(), events());
    const coordinates = [
      [-93.0, 45.0],
      [-92.99, 45.0],
    ] as const;
    api.setShapes([{ routeId: '18', directions: [{ directionId: 0, coordinates }] }]);
    api.setVehicles([fix({ lon: -92.995, lat: 45.0001 })], [VEHICLE]);

    const model = layer().model as MotionModel;
    const drawn = model.get('v1');

    expect(drawn?.lat).toBeCloseTo(45, 5);
    expect(drawn?.estimated).toBe(true);
    expect(api.estimate('v1')).toEqual({ fixAgeSeconds: 0, estimated: true });
  });

  it('delegates picking and screen positions to the layer', () => {
    const map = createMap();
    const api = enter(map, options(), events());

    api.pick(10, 20);
    api.screenPosition('v1');

    expect(layer().pick).toHaveBeenCalledWith(10, 20);
    expect(layer().screenPosition).toHaveBeenCalledWith('v1');
  });

  it('removes the 3D layers and restores the 2D vehicle layers on dispose', () => {
    const map = createMap();
    map.addLayer({ id: 'vehicles' });
    const api = enter(map, options(), events());
    expect(map.setLayoutProperty).toHaveBeenCalledWith('vehicles', 'visibility', 'none');

    api.dispose();

    expect(map.removeLayer).toHaveBeenCalledWith('buildings-3d');
    expect(map.getLayer('buildings-3d')).toBeUndefined();
    expect(map.setLayoutProperty).toHaveBeenCalledWith('vehicles', 'visibility', 'visible');
    expect(map.dragRotate.disable).toHaveBeenCalled();
    expect(map.setMaxPitch).toHaveBeenLastCalledWith(0);
    expect(map.jumpTo).toHaveBeenCalledWith({ pitch: 0, bearing: 0 });
  });
});
