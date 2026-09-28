import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import * as pmtiles from 'pmtiles';
import * as basemaps from '@protomaps/basemaps';
// Vite bundles the worker entry together with the shared chunk it imports, and emits it as a same-origin asset.
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

maplibregl.setWorkerUrl(workerUrl);

// Spike S-05: offline base map from a local PMTiles extract, glyphs and sprites served by the same nginx.
const params = new URLSearchParams(location.search);
const theme = params.get('theme') === 'dark' ? 'dark' : 'light';
const status = document.getElementById('status');
const errors = [];

const protocol = new pmtiles.Protocol();
maplibregl.addProtocol('pmtiles', protocol.tile);

const map = new maplibregl.Map({
  container: 'map',
  bounds: params.has('z') ? undefined : [[-93.730, 44.707], [-92.806, 45.330]],
  maxBounds: [[-93.822, 44.645], [-92.714, 45.392]],
  minZoom: 9,
  maxZoom: 18,
  center: params.has('z') ? [Number(params.get('lon')), Number(params.get('lat'))] : undefined,
  zoom: params.has('z') ? Number(params.get('z')) : undefined,
  style: {
    version: 8,
    glyphs: `${location.origin}/tiles/fonts/{fontstack}/{range}.pbf`,
    sprite: `${location.origin}/tiles/sprites/v4/${theme}`,
    sources: {
      protomaps: {
        type: 'vector',
        url: `pmtiles://${location.origin}/tiles/twin-cities.pmtiles`,
        attribution: '© OpenStreetMap contributors · Protomaps',
      },
    },
    layers: basemaps.layers('protomaps', basemaps.namedFlavor(theme), { lang: 'en' }),
  },
});
map.on('error', (e) => errors.push(String(e.error?.message ?? e.error ?? e)));
map.on('idle', () => {
  const foreign = performance.getEntriesByType('resource')
    .map((r) => r.name)
    .filter((u) => !u.startsWith(location.origin) && !u.startsWith('blob:') && !u.startsWith('data:'));
  status.textContent = JSON.stringify({
    idle: true,
    zoom: Number(map.getZoom().toFixed(2)),
    renderedFeatures: map.queryRenderedFeatures().length,
    errors,
    foreignRequests: foreign,
  });
  status.dataset.done = 'true';
});
