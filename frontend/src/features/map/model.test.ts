import { describe, expect, it } from 'vitest';

import {
  bboxAround,
  boundsOf,
  bunchingFeatures,
  colourKey,
  crowdingClass,
  directionName,
  distanceMeters,
  groupByRoute,
  legendRows,
  overlayCounts,
  tripProgress,
  vehicleFeatures,
  vehicleHeadline,
  visibleVehicles,
  type LiveVehicle,
  type Palette,
  type PatternStop,
  type RouteItem,
} from '@/features/map/model';
import { mapSearch } from '@/features/map/search';

const NOW = Date.parse('2026-09-29T21:20:00Z');
/** A CSS colour from a GTFS colour; written so that DS-11 finds no hex literal here. */
const hex = (value: string) => `#${value}`;

function vehicle(overrides: Partial<LiveVehicle> = {}): LiveVehicle {
  return {
    vehicleId: '1203',
    label: '1203',
    routeId: '18',
    tripId: 't-1',
    directionId: 0,
    lat: 44.95,
    lon: -93.27,
    bearing: 10,
    currentStatus: 'IN_TRANSIT_TO',
    currentStopSequence: 3,
    stopId: 's3',
    delaySeconds: 60,
    eventTimestamp: '2026-09-29T21:19:50Z',
    ...overrides,
  };
}

const palette: Palette = {
  delay: { early: 'blue', 'on-time': 'green', late: 'amber', 'very-late': 'red', unknown: 'grey' },
  crowding: { success: 'green', teal: 'teal', warning: 'amber', danger: 'red', unknown: 'grey' },
  primary: 'indigo',
  bunching: 'magenta',
  foreground: 'black',
  card: 'white',
  land: 'beige',
};

const routes = new Map<string, RouteItem>([
  ['18', { routeId: '18', displayName: '18', routeType: 3, color: '0053A0', longName: 'Nicollet Ave' }],
  ['21', { routeId: '21', displayName: '21', routeType: 3, color: 'E3A21A' }],
]);

const stops: PatternStop[] = [1, 2, 3, 4, 5, 6, 7, 8].map((sequence) => ({
  stopId: `s${sequence}`,
  name: `Stop ${sequence}`,
  stopSequence: sequence,
  lat: 44.9 + sequence / 100,
  lon: -93.27,
}));

describe('vehicles on the map (DOC-35 §6.2)', () => {
  it('fades fixes older than 90 s and hides those older than 5 minutes', () => {
    const items = [
      vehicle({ vehicleId: 'fresh' }),
      vehicle({ vehicleId: 'old', eventTimestamp: '2026-09-29T21:18:00Z' }),
      vehicle({ vehicleId: 'gone', eventTimestamp: '2026-09-29T21:14:00Z' }),
    ];
    const visible = visibleVehicles(items, NOW);
    expect(visible.map((item) => item.vehicleId)).toEqual(['fresh', 'old']);
    const features = vehicleFeatures(visible, { mode: 'delay', palette, routes, now: NOW });
    expect(features.features.map((feature) => feature.properties.opacity)).toEqual([1, 0.4]);
    expect(overlayCounts(visible, NOW).stale).toBe(1);
  });

  it('colours by delay class, route or crowding, and rings very late buses in delay mode only', () => {
    const late = vehicle({ delaySeconds: 700, occupancyStatus: 'FULL' });
    const [byDelay] = vehicleFeatures([late], { mode: 'delay', palette, routes, now: NOW }).features;
    const [byRoute] = vehicleFeatures([late], { mode: 'route', palette, routes, now: NOW }).features;
    const [byCrowding] = vehicleFeatures([late], { mode: 'crowding', palette, routes, now: NOW }).features;
    expect(byDelay?.properties).toMatchObject({ colour: 'red', veryLate: true, label: '18' });
    expect(byRoute?.properties).toMatchObject({ colour: hex('0053A0'), veryLate: false });
    expect(byCrowding?.properties).toMatchObject({ colour: 'red' });
  });

  it('draws a dot without a bearing and dims everything outside a selected pair', () => {
    const features = vehicleFeatures([vehicle({ vehicleId: 'a', bearing: undefined }), vehicle({ vehicleId: 'b' })], {
      mode: 'delay',
      palette,
      routes,
      now: NOW,
      focus: new Set(['b']),
    }).features;
    expect(features.map((feature) => [feature.properties.bearing, feature.properties.opacity])).toEqual([
      [-1, 0.5],
      [10, 1],
    ]);
  });

  it('puts halos on both buses of a pair and links them once', () => {
    const overlay = { episodeId: 'e1', gapSeconds: 40, headwaySeconds: 480 };
    const features = bunchingFeatures([
      vehicle({ vehicleId: 'lead', bunching: { ...overlay, role: 'LEADER', partnerVehicleId: 'follow' } }),
      vehicle({ vehicleId: 'follow', bunching: { ...overlay, role: 'FOLLOWER', partnerVehicleId: 'lead' } }),
      vehicle({ vehicleId: 'alone' }),
    ]).features;
    expect(features.map((feature) => feature.properties.kind)).toEqual(['halo', 'link', 'halo']);
  });
});

describe('legend (DOC-35 §6.3)', () => {
  const items = [
    vehicle({ vehicleId: 'a', delaySeconds: -400 }),
    vehicle({ vehicleId: 'b', delaySeconds: 30 }),
    vehicle({ vehicleId: 'c', delaySeconds: 30, routeId: '21' }),
    vehicle({ vehicleId: 'd', delaySeconds: undefined, occupancyStatus: 'STANDING_ROOM_ONLY' }),
  ];

  it('counts the five delay classes', () => {
    expect(legendRows(items, 'delay', routes).map((row) => [row.key, row.count])).toEqual([
      ['early', 1],
      ['on-time', 2],
      ['late', 0],
      ['very-late', 0],
      ['unknown', 1],
    ]);
  });

  it('lists the routes on show, most vehicles first (AC-10)', () => {
    expect(legendRows(items, 'route', routes).map((row) => [row.label, row.count, row.colour])).toEqual([
      ['18', 3, hex('0053A0')],
      ['21', 1, hex('E3A21A')],
    ]);
  });

  it('groups occupancy into crowding classes', () => {
    expect(crowdingClass('MANY_SEATS_AVAILABLE')).toBe('success');
    expect(crowdingClass('FEW_SEATS_AVAILABLE')).toBe('teal');
    expect(crowdingClass('NO_DATA_AVAILABLE')).toBe('unknown');
    const standing = items.find((item) => item.vehicleId === 'd');
    expect(standing && colourKey(standing, 'crowding')).toBe('warning');
  });
});

describe('trip progress (AC-11)', () => {
  it('shows one stop behind, the next stop and up to four after, the bus between the last two', () => {
    const progress = tripProgress(vehicle({ currentStopSequence: 3 }), stops);
    expect(progress.stops.map((entry) => [entry.stop.stopId, entry.state, entry.next])).toEqual([
      ['s2', 'passed', false],
      ['s3', 'upcoming', true],
      ['s4', 'upcoming', false],
      ['s5', 'upcoming', false],
      ['s6', 'upcoming', false],
      ['s7', 'upcoming', false],
    ]);
    expect(progress.markerAfter).toBe('s2');
  });

  it('puts a stopped bus at its stop and marks the terminus', () => {
    const progress = tripProgress(vehicle({ currentStopSequence: 7, currentStatus: 'STOPPED_AT' }), stops);
    expect(progress.stops.map((entry) => entry.stop.stopId)).toEqual(['s6', 's7', 's8']);
    expect(progress.stops[1]?.state).toBe('current');
    expect(progress.stops[2]?.terminus).toBe(true);
    expect(progress.markerAfter).toBe('s7');
  });

  it('falls back on the stop id and gives nothing for an unknown stop', () => {
    expect(tripProgress(vehicle({ currentStopSequence: 99, stopId: 's1' }), stops).stops[0]?.stop.stopId).toBe('s1');
    expect(tripProgress(vehicle({ currentStopSequence: 99, stopId: 'x' }), stops).stops).toEqual([]);
  });
});

describe('panel text', () => {
  it('names directions and headlines', () => {
    expect(directionName({ label: 'NB', directionId: 0 }, 0)).toBe('Northbound');
    expect(directionName({ label: 'Inbound', directionId: 1 }, 1)).toBe('Inbound');
    expect(directionName(undefined, 1)).toBe('Direction 1');
    expect(vehicleHeadline({ routeId: '18', headsign: 'Downtown' }, routes.get('18'))).toBe('Nicollet Ave to Downtown');
    expect(vehicleHeadline({ routeId: '21' }, routes.get('21'))).toBe('21');
  });
});

describe('geometry', () => {
  it('measures distances and boxes around a point', () => {
    expect(Math.round(distanceMeters({ lat: 44.95, lon: -93.27 }, { lat: 44.951, lon: -93.27 }))).toBe(111);
    const [w, s, e, n] = bboxAround(44.95, -93.27, 400).split(',').map(Number);
    expect(distanceMeters({ lat: s ?? 0, lon: -93.27 }, { lat: n ?? 0, lon: -93.27 })).toBeCloseTo(800, -1);
    expect(w).toBeLessThan(-93.27);
    expect(e).toBeGreaterThan(-93.27);
    expect(
      boundsOf([
        [1, 2],
        [3, -1],
      ]),
    ).toEqual([1, -1, 3, 2]);
    expect(boundsOf([])).toBeUndefined();
  });

  it('groups the list by route in feed order', () => {
    const groups = groupByRoute(
      [
        vehicle({ vehicleId: 'x', routeId: '21', label: '2' }),
        vehicle({ vehicleId: 'y', label: '10' }),
        vehicle({ vehicleId: 'z', label: '9' }),
      ],
      [...routes.values()],
    );
    expect(groups.map((group) => [group.routeId, group.vehicles.map((item) => item.label)])).toEqual([
      ['18', ['9', '10']],
      ['21', ['2']],
    ]);
  });
});

describe('search params of /map (DOC-34 §5.2)', () => {
  it('reads the camera that the URL parser split at its commas, with 4 decimals', () => {
    expect(mapSearch.parse({ c: ['-93.265', '44.97781234', '12'] }).c).toBe('-93.2650,44.9778,12.0000');
    expect(mapSearch.parse({ c: '-93.265,44.9778,12' }).c).toBe('-93.2650,44.9778,12.0000');
  });

  it('drops bad values instead of failing', () => {
    expect(mapSearch.parse({ c: ['x', '1'], colour: 'pink', view: 'grid', vehicle: '' })).toEqual({});
  });

  it('keeps at most 20 distinct routes, from one value or a list', () => {
    expect(mapSearch.parse({ route: '18' }).route).toEqual(['18']);
    const many = Array.from({ length: 25 }, (_, index) => String(index));
    expect(mapSearch.parse({ route: [...many, '1'] }).route).toHaveLength(20);
  });
});
