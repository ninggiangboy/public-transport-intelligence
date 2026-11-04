import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import * as pmtiles from 'pmtiles';
import * as basemaps from '@protomaps/basemaps';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

import { makeFleet, makeRoutes, DOWNTOWN } from './sim.js';
import * as motion from './motion.js';

maplibregl.setWorkerUrl(workerUrl);

const params = new URLSearchParams(location.search);
const origin = location.origin;
const implName = params.get('impl') === 'deck' ? 'deck' : 'custom';
const interleaved = params.get('interleaved') === '1';
const flavor = 'grayscale';

const protocol = new pmtiles.Protocol();
maplibregl.addProtocol('pmtiles', protocol.tile);

const layers = basemaps.layers('protomaps', basemaps.namedFlavor(flavor), { lang: 'en' });
// Buildings: DR-111 wants fill-extrusion from the existing `buildings` source-layer, height
// coalesced to 8 m, min_height (building:part) respected, hidden below zoom 13.
layers.push({
  id: 'buildings-3d',
  type: 'fill-extrusion',
  source: 'protomaps',
  'source-layer': 'buildings',
  minzoom: 13,
  paint: {
    'fill-extrusion-color': [
      'interpolate', ['linear'], ['zoom'],
      15, '#d8d3c8',
      16.5, '#cbc4b6',
    ],
    'fill-extrusion-height': ['coalesce', ['to-number', ['get', 'height']], 8],
    'fill-extrusion-base': ['coalesce', ['to-number', ['get', 'min_height']], 0],
    'fill-extrusion-opacity': 0.85,
  },
});

const map = new maplibregl.Map({
  container: 'map',
  center: [DOWNTOWN.lon, DOWNTOWN.lat],
  zoom: Number(params.get('zoom') ?? 15.5),
  pitch: Number(params.get('pitch') ?? 60),
  bearing: Number(params.get('bearing') ?? -22),
  minZoom: 12,
  maxZoom: 18,
  antialias: true,
  style: {
    version: 8,
    glyphs: `${origin}/tiles/fonts/{fontstack}/{range}.pbf`,
    sprite: `${origin}/tiles/sprites/v4/${flavor}`,
    sources: {
      protomaps: {
        type: 'vector',
        url: `pmtiles://${origin}/tiles/twin-cities.pmtiles`,
        attribution: '© OpenStreetMap contributors · Protomaps',
      },
    },
    layers,
  },
});

const errors = [];
map.on('error', (e) => errors.push(String(e.error?.message ?? e.error ?? e)));

const status = document.getElementById('status');

function rendererString() {
  try {
    const gl = map.getCanvas().getContext('webgl2');
    const dbg = gl.getExtension('WEBGL_debug_renderer_info');
    return dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
  } catch (e) {
    return `unavailable (${e})`;
  }
}

map.on('load', async () => {
  const routes = makeRoutes(40);
  const vehicles = makeFleet(routes, 15);
  const ctx = {
    map,
    vehicles,
    motion,
    now: () => performance.now() / 1000,
    impl: implName,
    interleaved,
  };
  window.__s07 = {
    impl: implName,
    interleaved,
    routeCount: routes.length,
    vehicleCount: vehicles.length,
    pitch: map.getPitch(),
    center: [DOWNTOWN.lon, DOWNTOWN.lat],
    map,
    errors,
  };
  try {
    const mod = await import(implName === 'deck' ? './impl-deck.js' : './impl-custom.js');
    const handle = mod.start(ctx);
    window.__s07.stop = handle.stop;
    window.__s07.positions = handle.positions;
    window.__s07.renderer = rendererString();
    status.textContent = JSON.stringify({ ready: true, impl: implName, vehicles: vehicles.length, renderer: window.__s07.renderer }, null, 0);
    status.dataset.ready = 'true';
  } catch (e) {
    errors.push(String(e?.stack ?? e));
    status.textContent = `impl error: ${e}`;
    status.dataset.ready = 'error';
  }
});
