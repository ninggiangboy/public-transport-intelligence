import type { FeatureCollection } from 'geojson';
import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';

import { LABEL_FONT } from '@/features/map/baseStyle';
import type { SourceId } from '@/features/map/handle';
import type { Palette } from '@/features/map/model';
import { ARROW_ICON, DOT_ICON, vehicleIcons } from '@/features/map/vehicle-icon';

// The data layers of DOC-35 §6.2, bottom to top, written straight into MapLibre: positions change many times a second
// and must not go through React (DOC-34 §7). A style change (theme) drops every layer, so `install` runs again on each
// `style.load` with the data last given.

const SOURCES: readonly SourceId[] = ['routes', 'disruption-stops', 'stops', 'vehicles', 'bunching', 'selection'];

/** Layers a click or a hover reacts to. */
export const INTERACTIVE_LAYERS = ['vehicle-clusters', 'bunching-halo', 'vehicles', 'stops'];

/** `clusterMaxZoom` of the vehicles: below zoom 12 they gather in numbered circles (AC-2). */
export const CLUSTER_MAX_ZOOM = 11;
export const STOPS_MIN_ZOOM = 14;

const EMPTY: FeatureCollection = { type: 'FeatureCollection', features: [] };

const NOT_CLUSTER: ['!', ['has', string]] = ['!', ['has', 'point_count']];

export class MapScene {
  private readonly data = new Map<SourceId, FeatureCollection>();

  constructor(
    private readonly map: MapLibreMap,
    private palette: Palette,
  ) {}

  /** New colours (a theme change on a style that stays): the layers are added again with them. */
  setPalette(palette: Palette) {
    this.palette = palette;
    // A style still loading (setStyle of a theme change) gets the new colours from its own `style.load`.
    if (!this.map.isStyleLoaded()) return;
    for (const id of this.layerIds) {
      if (this.map.getLayer(id)) this.map.removeLayer(id);
    }
    this.install();
  }

  private layerIds: string[] = [];

  /** Adds the icons, sources and layers to the current style, with the data last set. */
  install() {
    const map = this.map;
    const palette = this.palette;
    const icons = vehicleIcons();
    for (const [id, image] of [
      [ARROW_ICON, icons.arrow],
      [DOT_ICON, icons.dot],
    ] as const) {
      if (image && !map.hasImage(id)) map.addImage(id, image, { sdf: true, pixelRatio: icons.pixelRatio });
    }
    for (const id of SOURCES) {
      if (map.getSource(id)) continue;
      map.addSource(id, {
        type: 'geojson',
        data: this.data.get(id) ?? EMPTY,
        ...(id === 'vehicles' ? { cluster: true, clusterMaxZoom: CLUSTER_MAX_ZOOM, clusterRadius: 40 } : {}),
      });
    }
    const add: Parameters<MapLibreMap['addLayer']>[0][] = [
      {
        id: 'routes-casing',
        type: 'line',
        source: 'routes',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': palette.card, 'line-width': ['step', ['zoom'], 5, 12, 7] },
      },
      {
        id: 'routes-line',
        type: 'line',
        source: 'routes',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'colour'], 'line-width': ['step', ['zoom'], 3, 12, 5] },
      },
      {
        id: 'disruption-stops',
        type: 'circle',
        source: 'disruption-stops',
        paint: {
          'circle-radius': 9,
          'circle-color': palette.card,
          'circle-stroke-color': palette.delay['very-late'],
          'circle-stroke-width': 3,
        },
      },
      {
        id: 'disruption-stops-mark',
        type: 'symbol',
        source: 'disruption-stops',
        layout: { 'text-field': '!', 'text-font': LABEL_FONT, 'text-size': 12, 'text-allow-overlap': true },
        paint: { 'text-color': palette.delay['very-late'] },
      },
      {
        id: 'stops',
        type: 'circle',
        source: 'stops',
        minzoom: STOPS_MIN_ZOOM,
        paint: {
          'circle-radius': ['case', ['get', 'selected'], 7, 4],
          'circle-color': palette.card,
          'circle-stroke-color': ['case', ['get', 'selected'], palette.primary, ['get', 'colour']],
          'circle-stroke-width': 2.5,
        },
      },
      {
        id: 'vehicle-clusters',
        type: 'circle',
        source: 'vehicles',
        filter: ['has', 'point_count'],
        paint: {
          'circle-color': palette.foreground,
          'circle-radius': ['step', ['get', 'point_count'], 13, 10, 16, 50, 20],
          'circle-stroke-color': palette.card,
          'circle-stroke-width': 2,
        },
      },
      {
        id: 'vehicle-cluster-count',
        type: 'symbol',
        source: 'vehicles',
        filter: ['has', 'point_count'],
        layout: {
          'text-field': ['get', 'point_count_abbreviated'],
          'text-font': LABEL_FONT,
          'text-size': 12,
          'text-allow-overlap': true,
        },
        paint: { 'text-color': palette.card },
      },
      {
        id: 'bunching-links',
        type: 'line',
        source: 'bunching',
        filter: ['==', ['get', 'kind'], 'link'],
        paint: { 'line-color': palette.bunching, 'line-width': 2, 'line-dasharray': [2, 2] },
      },
      {
        id: 'bunching-halo',
        type: 'circle',
        source: 'bunching',
        filter: ['==', ['get', 'kind'], 'halo'],
        paint: {
          'circle-radius': 16,
          'circle-color': palette.bunching,
          'circle-opacity': 0.08,
          'circle-stroke-color': palette.bunching,
          'circle-stroke-width': 3,
        },
      },
      {
        id: 'vehicles-very-late',
        type: 'circle',
        source: 'vehicles',
        filter: ['all', NOT_CLUSTER, ['get', 'veryLate']],
        paint: {
          'circle-radius': 13,
          'circle-opacity': 0,
          'circle-stroke-color': palette.delay['very-late'],
          'circle-stroke-width': 1.5,
          'circle-stroke-opacity': ['get', 'opacity'],
        },
      },
      {
        id: 'vehicles',
        type: 'symbol',
        source: 'vehicles',
        filter: NOT_CLUSTER,
        layout: {
          'icon-image': ['case', ['>=', ['get', 'bearing'], 0], ARROW_ICON, DOT_ICON],
          'icon-rotate': ['max', ['get', 'bearing'], 0],
          'icon-rotation-alignment': 'map',
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
        },
        paint: {
          'icon-color': ['get', 'colour'],
          'icon-opacity': ['get', 'opacity'],
          'icon-halo-color': 'white',
          'icon-halo-width': 1.5,
        },
      },
      {
        id: 'vehicle-selected',
        type: 'circle',
        source: 'selection',
        paint: {
          'circle-radius': 14,
          'circle-opacity': 0,
          'circle-stroke-color': palette.primary,
          'circle-stroke-width': 3,
        },
      },
      {
        id: 'vehicle-labels',
        type: 'symbol',
        source: 'vehicles',
        minzoom: STOPS_MIN_ZOOM,
        filter: NOT_CLUSTER,
        layout: {
          'text-field': ['get', 'label'],
          'text-font': LABEL_FONT,
          'text-size': 11,
          'text-anchor': 'left',
          'text-offset': [1.3, 0],
          'text-optional': true,
        },
        paint: {
          'text-color': palette.foreground,
          'text-halo-color': palette.card,
          'text-halo-width': 1.5,
          'text-opacity': ['get', 'opacity'],
        },
      },
    ];
    this.layerIds = add.map((layer) => layer.id);
    for (const layer of add) {
      if (!map.getLayer(layer.id)) map.addLayer(layer);
    }
  }

  /** Replaces the features of a source; kept for the next `install`. */
  set(id: SourceId, collection: FeatureCollection) {
    this.data.set(id, collection);
    void this.map.getSource<GeoJSONSource>(id)?.setData(collection);
  }

  /** The zoom at which a cluster falls apart, for a click on it. */
  async clusterZoom(clusterId: number): Promise<number> {
    const source = this.map.getSource<GeoJSONSource>('vehicles');
    return source ? source.getClusterExpansionZoom(clusterId) : this.map.getZoom() + 2;
  }
}
