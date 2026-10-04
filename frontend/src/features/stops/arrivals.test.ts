import { describe, expect, it } from 'vitest';

import { listStopArrivals } from '@/api/generated/examples';
import {
  directionLabel,
  directionsOf,
  etaDisplay,
  routeDirection,
  visibleArrivals,
  type Arrival,
} from '@/features/stops/arrivals';
import { withStop } from '@/lib/stop-lists';

const [base] = listStopArrivals.examples.arrivals.items;
if (!base) throw new Error('example has no arrival');
const CHICAGO = 'America/Chicago';
const NOW = Date.parse('2026-09-29T21:19:35Z');

function trip(overrides: Partial<Arrival>): Arrival {
  return { ...base, ...overrides } as Arrival;
}

describe('etaDisplay (DOC-37 §4.4)', () => {
  it('counts down: Due within a minute, minutes below an hour, then the clock time', () => {
    expect(etaDisplay('2026-09-29T21:20:30Z', NOW, CHICAGO)).toEqual({ kind: 'due' });
    expect(etaDisplay('2026-09-29T21:25:04Z', NOW, CHICAGO)).toEqual({ kind: 'minutes', minutes: 5 });
    expect(etaDisplay('2026-09-29T22:19:34Z', NOW, CHICAGO)).toEqual({ kind: 'minutes', minutes: 59 });
    expect(etaDisplay('2026-09-29T22:19:35Z', NOW, CHICAGO)).toEqual({ kind: 'time', time: '5:19', unit: 'PM' });
  });

  it('AC-4 decreases as businessNow moves on, without a refetch', () => {
    const at = '2026-09-29T21:29:35Z';
    expect(etaDisplay(at, NOW, CHICAGO)).toEqual({ kind: 'minutes', minutes: 10 });
    expect(etaDisplay(at, NOW + 5 * 60_000, CHICAGO)).toEqual({ kind: 'minutes', minutes: 5 });
  });

  it('shows the scheduled clock time for schedule-only trips', () => {
    expect(etaDisplay('2026-09-29T21:25:04Z', NOW, CHICAGO, true)).toEqual({ kind: 'time', time: '4:25', unit: 'PM' });
  });
});

describe('visibleArrivals', () => {
  const trips = [
    trip({ tripId: 'gone', predictedArrival: '2026-09-29T21:18:00Z' }),
    trip({ tripId: 'due', predictedArrival: '2026-09-29T21:19:00Z' }),
    trip({ tripId: 'b', routeId: '46', directionId: 1 }),
  ];

  it('hides trips more than a minute in the past', () => {
    expect(visibleArrivals(trips, NOW).map((t) => t.tripId)).toEqual(['due', 'b']);
  });

  it('filters by route and direction', () => {
    expect(visibleArrivals(trips, NOW, { routes: ['46'] }).map((t) => t.tripId)).toEqual(['b']);
    expect(visibleArrivals(trips, NOW, { dir: 0 }).map((t) => t.tripId)).toEqual(['due']);
  });

  it('prefers the real-time arrival when there is one', () => {
    const live = trip({ predictedArrival: '2026-09-29T21:10:00Z', realtimeArrival: '2026-09-29T21:21:00Z' });
    expect(visibleArrivals([live], NOW)).toHaveLength(1);
  });

  it('finds the directions and the usual direction of a route', () => {
    expect(directionsOf(trips)).toEqual([0, 1]);
    expect(routeDirection(trips, '18')).toBe(0);
    expect(routeDirection(trips, '46')).toBe(1);
    expect(routeDirection(trips, '99')).toBeUndefined();
  });
});

describe('labels and lists', () => {
  it('names directions from the E-02 label (DOC-37 §3.2)', () => {
    expect(directionLabel('NB', 0)).toBe('Northbound');
    expect(directionLabel('Loop', 0)).toBe('Loop');
    expect(directionLabel(undefined, 1)).toBe('Direction 1');
  });

  it('keeps a stop list unique, newest first and bounded', () => {
    const list = [
      { stopId: 'a', name: 'A' },
      { stopId: 'b', name: 'B' },
    ];
    expect(withStop(list, { stopId: 'b', name: 'B' }, 5).map((s) => s.stopId)).toEqual(['b', 'a']);
    expect(withStop(list, { stopId: 'c', name: 'C' }, 2).map((s) => s.stopId)).toEqual(['c', 'a']);
  });
});
