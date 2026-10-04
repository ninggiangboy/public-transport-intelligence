import 'maplibre-gl/dist/maplibre-gl.css';
import '@/features/map/map.css';

import { Map as MapView, Marker, Popup, type MapLayerMouseEvent, type MapRef } from '@vis.gl/react-maplibre';
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification } from 'maplibre-gl';
// MapLibre 6 looks for its worker next to the bundle; Vite emits it as a same-origin asset instead (ADR-0021).
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { Protocol } from 'pmtiles';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import { useEnv } from '@/app/env-context';
import { useTheme } from '@/app/theme-provider';
import { Callout } from '@/components/Callout';
import { offlineBaseAvailable, offlineStyle, ONLINE_STYLE_URL, plainStyle } from '@/features/map/baseStyle';
import type { MapHandle, MapPick } from '@/features/map/handle';
import { FEED_BOUNDS, MAX_BOUNDS } from '@/features/map/model';
import { readPalette, usePalette } from '@/features/map/palette';
import { INTERACTIVE_LAYERS, MapScene } from '@/features/map/scene';
import { parseCamera, type Camera } from '@/features/map/search';
import { mapCopy } from '@/i18n/map';
import { useReducedMotion } from '@/lib/use-reduced-motion';

// The map itself (DOC-35 §5.8, §6): MapLibre through react-maplibre, the base map of ADR-0021 and the data layers of
// scene.ts. The screen talks to it through a MapHandle only.

const copy = mapCopy.map;

let protocolInstalled = false;
function installProtocol() {
  if (protocolInstalled) return;
  maplibregl.addProtocol('pmtiles', new Protocol().tile);
  protocolInstalled = true;
}

const MOTION_MS = 600;
const POINTER = 'pointer';

interface MapCanvasProps {
  /** `c` of the URL; without it the camera fits the feed area. */
  initialCamera?: string;
  onReady: (handle: MapHandle | undefined) => void;
  /** After every pan or zoom, the user's or the screen's. */
  onMoveEnd: (camera: Camera) => void;
  /** A drag by the user: ends "Follow" (screens/live-map §6). */
  onUserMove: () => void;
  onPick: (pick: MapPick | undefined) => void;
  children?: ReactNode;
}

type BaseMap = 'checking' | 'available' | 'missing';

export function MapCanvas({ initialCamera, onReady, onMoveEnd, onUserMove, onPick, children }: MapCanvasProps) {
  const appEnv = useEnv();
  const { resolved: theme } = useTheme();
  const reducedMotion = useReducedMotion();
  const [baseMap, setBaseMap] = useState<BaseMap>(appEnv.mapStyle === 'online' ? 'available' : 'checking');
  const [cursor, setCursor] = useState<string>();
  const ref = useRef<MapRef>(null);
  const scene = useRef<MapScene>(undefined);

  installProtocol();

  useEffect(() => {
    if (appEnv.mapStyle === 'online') return;
    let active = true;
    void offlineBaseAvailable(theme).then((available) => {
      if (active) setBaseMap(available ? 'available' : 'missing');
    });
    return () => {
      active = false;
    };
  }, [appEnv.mapStyle, theme]);

  // Follows the class the theme provider puts on <html>, which lands after this render (palette.ts).
  const palette = usePalette();
  const style = useMemo<StyleSpecification | string | undefined>(() => {
    if (baseMap === 'checking') return undefined;
    if (baseMap === 'missing') return plainStyle(palette.land);
    return appEnv.mapStyle === 'online' ? ONLINE_STYLE_URL : offlineStyle(theme);
  }, [appEnv.mapStyle, baseMap, theme, palette.land]);

  // A theme change on a style that stays the same (online) still recolours the data layers.
  useEffect(() => {
    scene.current?.setPalette(palette);
  }, [palette]);

  const handle = useCallback(
    (map: maplibregl.Map, current: MapScene): MapHandle => ({
      set: (source, collection) => {
        current.set(source, collection);
      },
      fitBounds: (bounds, padding = 64) => {
        map.fitBounds(bounds, { padding, maxZoom: 16, duration: reducedMotion ? 0 : MOTION_MS });
      },
      moveTo: (lon, lat, zoom) => {
        const options = { center: [lon, lat] as [number, number], ...(zoom === undefined ? {} : { zoom }) };
        if (reducedMotion) map.jumpTo(options);
        else map.easeTo({ ...options, duration: MOTION_MS });
      },
      zoomBy: (delta) => {
        map.zoomTo(map.getZoom() + delta, { duration: reducedMotion ? 0 : 200 });
      },
      camera: () => {
        const center = map.getCenter();
        return { lon: center.lng, lat: center.lat, zoom: map.getZoom() };
      },
      clusterZoom: (clusterId) => current.clusterZoom(clusterId),
    }),
    [reducedMotion],
  );

  const onLoad = useCallback(() => {
    const map = ref.current?.getMap();
    if (!map) return;
    map.getCanvas().setAttribute('aria-label', copy.canvasLabel);
    const created = new MapScene(map, readPalette());
    created.install();
    // setStyle (a theme change) drops every layer; put them back on the new style.
    map.on('style.load', () => {
      created.install();
    });
    scene.current = created;
    onReady(handle(map, created));
  }, [handle, onReady]);

  // The handle closes over `reducedMotion`; hand out a new one when it changes.
  useEffect(() => {
    const map = ref.current?.getMap();
    if (map && scene.current) onReady(handle(map, scene.current));
  }, [handle, onReady]);

  useEffect(
    () => () => {
      onReady(undefined);
    },
    [onReady],
  );

  const onClick = (event: MapLayerMouseEvent) => {
    const feature = event.features?.[0];
    onPick(
      feature
        ? {
            layer: feature.layer.id,
            properties: { ...feature.properties },
            lon: event.lngLat.lng,
            lat: event.lngLat.lat,
          }
        : undefined,
    );
  };

  const camera = parseCamera(initialCamera);
  return (
    <div className="absolute inset-0 bg-(--map-land)">
      {style === undefined ? null : (
        <MapView
          ref={ref}
          mapLib={maplibregl}
          workerUrl={workerUrl}
          mapStyle={style}
          styleDiffing={false}
          initialViewState={
            camera
              ? { longitude: camera.lon, latitude: camera.lat, zoom: camera.zoom }
              : { bounds: FEED_BOUNDS, fitBoundsOptions: { padding: 24 } }
          }
          minZoom={9}
          maxZoom={18}
          maxBounds={MAX_BOUNDS}
          attributionControl={false}
          dragRotate={false}
          pitchWithRotate={false}
          interactiveLayerIds={INTERACTIVE_LAYERS}
          cursor={cursor}
          onLoad={onLoad}
          onClick={onClick}
          onMouseEnter={() => {
            setCursor(POINTER);
          }}
          onMouseLeave={() => {
            setCursor(undefined);
          }}
          onDragStart={onUserMove}
          onMoveEnd={(event) => {
            const center = event.viewState;
            onMoveEnd({ lon: center.longitude, lat: center.latitude, zoom: center.zoom });
          }}
          style={{ position: 'absolute', inset: 0 }}
        >
          {children}
        </MapView>
      )}
      {baseMap === 'missing' ? (
        <div className="pointer-events-none absolute inset-x-0 bottom-14 flex justify-center px-4">
          <div className="pointer-events-auto max-w-md">
            <Callout tone="warning">{copy.baseMapMissing}</Callout>
          </div>
        </div>
      ) : null}
      <p className="pointer-events-none absolute right-2 bottom-1 z-10 rounded bg-card/80 px-1.5 text-[11px] text-muted-foreground">
        {copy.attribution}
      </p>
    </div>
  );
}

/** The "ping" around the selected vehicle or the first stop of the open disruption (DOC-35 §4.4). */
export function MapPing({ lon, lat, tone }: { lon: number; lat: number; tone: 'primary' | 'danger' }) {
  return (
    <Marker longitude={lon} latitude={lat} anchor="center" style={{ pointerEvents: 'none' }}>
      <span
        aria-hidden="true"
        className={
          tone === 'primary'
            ? 'block size-8 animate-map-ping rounded-full bg-primary/35'
            : 'block size-6 animate-map-ping rounded-full bg-delay-very-late/35'
        }
      />
    </Marker>
  );
}

/** A small popover anchored on the map, for a stop picked on the map or in the search. */
export function MapPopup({
  lon,
  lat,
  onClose,
  children,
}: {
  lon: number;
  lat: number;
  onClose: () => void;
  children: ReactNode;
}) {
  return (
    <Popup
      longitude={lon}
      latitude={lat}
      anchor="bottom"
      offset={12}
      closeButton={false}
      onClose={onClose}
      className="pti-map-popup"
    >
      {children}
    </Popup>
  );
}
