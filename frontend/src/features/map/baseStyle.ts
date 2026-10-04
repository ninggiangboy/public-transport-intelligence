import { layers, namedFlavor } from '@protomaps/basemaps';
import type { LayerSpecification, StyleSpecification } from 'maplibre-gl';

import { mapCopy } from '@/i18n/map';

// The base map (ADR-0021, DOC-35 §6.1). `offline` reads the PMTiles extract of `make tiles` from /tiles/ on the same
// origin; `online` is the OpenFreeMap style used while developing (DR-47). Without the extract the map keeps a plain
// background and every data layer.

const TILES_PATH = '/tiles/twin-cities.pmtiles';
export const ONLINE_STYLE_URL = 'https://tiles.openfreemap.org/styles/positron';
/** Font of the data layers' labels; both the Protomaps assets and OpenFreeMap serve it. */
export const LABEL_FONT = ['Noto Sans Regular'];

export type Theme = 'light' | 'dark';

/** Neutral flavours, so that route and status colours stand out (DR-88 §S-05, ADR-0021). */
function flavorOf(theme: Theme) {
  return theme === 'dark' ? 'black' : 'grayscale';
}

function origin() {
  return globalThis.location.origin;
}

export function offlineStyle(theme: Theme): StyleSpecification {
  const flavor = flavorOf(theme);
  return {
    version: 8,
    glyphs: `${origin()}/tiles/fonts/{fontstack}/{range}.pbf`,
    sprite: `${origin()}/tiles/sprites/v4/${flavor}`,
    sources: {
      protomaps: {
        type: 'vector',
        url: `pmtiles://${origin()}${TILES_PATH}`,
        attribution: mapCopy.map.attribution,
      },
    },
    // @protomaps/basemaps types its layers with an older style-spec package; the JSON is the same.
    layers: layers('protomaps', namedFlavor(flavor), { lang: 'en' }) as unknown as LayerSpecification[],
  };
}

/** Only a background in the land colour of the theme; glyphs still come from /tiles/ when they are there. */
export function plainStyle(land: string): StyleSpecification {
  return {
    version: 8,
    glyphs: `${origin()}/tiles/fonts/{fontstack}/{range}.pbf`,
    sources: {},
    layers: [{ id: 'background', type: 'background', paint: { 'background-color': land } }],
  };
}

/** Whether the extract and the sprite of `make tiles` are served (ADR-0021 "Thiếu file"). */
export async function offlineBaseAvailable(theme: Theme): Promise<boolean> {
  const head = async (path: string) => {
    try {
      // Static files of the frontend's own nginx, not the API: src/api/ has nothing to say about them.
      // eslint-disable-next-line no-restricted-globals
      const response = await fetch(path, { method: 'HEAD' });
      // A dev server answers unknown paths with the SPA's index.html; nginx answers 404 (try_files … =404).
      return response.ok && !(response.headers.get('Content-Type') ?? '').includes('text/html');
    } catch {
      return false;
    }
  };
  const [tiles, sprite] = await Promise.all([head(TILES_PATH), head(`/tiles/sprites/v4/${flavorOf(theme)}.json`)]);
  return tiles && sprite;
}
