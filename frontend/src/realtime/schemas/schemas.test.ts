import { describe, expect, it } from 'vitest';

import { parseFrame } from '@/realtime/schemas';

const alert = {
  id: '0a4c7e1f-2b3d-5e6f-8a9b-1c2d3e4f5a6b',
  type: 'DISRUPTION',
  severity: 2,
  audience: 'PUBLIC',
  routeId: '18',
  title: 'Delays on route 18 northbound',
  body: { disruptionId: '9d8e7f6a' },
  createdAt: '2026-09-29T20:59:31Z',
  link: '/map?route=18',
};

const vehicle = {
  vehicleId: '1203',
  tripId: '27371245',
  directionId: 0,
  lat: 44.948121,
  lon: -93.278004,
  bearing: 358.0,
  speedMps: 7.4,
  currentStatus: 'IN_TRANSIT_TO',
  stopId: '51420',
  currentStopSequence: 14,
  occupancyStatus: 'MANY_SEATS_AVAILABLE',
  eventTimestamp: '2026-09-29T21:19:30Z',
};

function envelope(type: string, data: unknown, extra: object = {}) {
  return { id: '01J8ZK', type, channel: 'alerts', occurredAt: '2026-09-29T21:19:31.020Z', data, ...extra };
}

/** Every event type of DOC-33 §3 with the payload of §5. */
const samples: [string, unknown][] = [
  ['vehicles.batch', { routeId: '18', vehicles: [vehicle] }],
  [
    'bunching.opened',
    {
      id: 'b1',
      routeId: '18',
      directionId: 0,
      vehicleLeader: '1187',
      vehicleFollower: '1203',
      gapSeconds: 112,
      headwaySeconds: 600,
    },
  ],
  [
    'bunching.closed',
    { id: 'b1', routeId: '18', episodeEnd: '2026-09-29T21:30:00Z', closeReason: 'RECOVERED', minGapSeconds: 90 },
  ],
  ['disruption.opened', { id: 'd1', routeId: '18', zScore: 3.98, affectedStopIds: ['51418'] }],
  ['disruption.closed', { id: 'd1', routeId: '18', peakZScore: 4.4 }],
  [
    'dispatch.suggested',
    { id: 'x1', bunchingId: 'b1', routeId: '18', action: 'hold_follower', actionConfidence: 0.82 },
  ],
  ['alert.created', alert],
  ['alert.updated', { ...alert, resolvedAt: '2026-09-29T21:40:00Z' }],
  ['alert.retracted', { id: alert.id, routeId: '18' }],
  ['job.run', { runId: 'job:4127', kind: 'BATCH_JOB', name: 'RawZoneReplayJob', status: 'STARTED', readCount: 1 }],
  ['dlq.changed', { kind: 'CREATED', source: 'GTFS_RT_VEHICLE_POSITION', count: 12 }],
  ['dlq.changed', { kind: 'UPDATED', id: 'dl1', status: 'REPLAY_REQUESTED' }],
  ['dlq.changed', { kind: 'BULK_UPDATED', source: 'GTFS_RT_TRIP_UPDATE', status: 'RESOLVED', count: 938 }],
];

describe('parseFrame', () => {
  it.each(samples)('accepts %s (DOC-33 §5)', (type, data) => {
    const frame = parseFrame(type, JSON.stringify(envelope(type, data)));
    expect(frame.kind).toBe('event');
    if (frame.kind === 'event') expect(frame.event.type).toBe(type);
  });

  it('accepts resync and heartbeat, which have no id (§5.9, §5.10)', () => {
    const resync = parseFrame(
      'resync',
      '{"type":"resync","occurredAt":"2026-09-29T21:25:01Z","data":{"reason":"BUFFER_EXPIRED","channels":["alerts","jobs"]}}',
    );
    expect(resync).toMatchObject({ kind: 'event', event: { type: 'resync', data: { channels: ['alerts', 'jobs'] } } });

    const heartbeat = parseFrame(
      'heartbeat',
      '{"type":"heartbeat","data":{"serverTime":"2026-09-29T21:19:45.123Z","businessNow":"2026-09-29T21:19:45.123Z"}}',
    );
    expect(heartbeat).toMatchObject({ kind: 'event', event: { data: { businessNow: '2026-09-29T21:19:45.123Z' } } });
  });

  it('lets unknown fields pass and drops them', () => {
    const frame = parseFrame(
      'alert.retracted',
      JSON.stringify(envelope('alert.retracted', { id: 'a', extra: 1 }, { more: true })),
    );
    expect(frame).toMatchObject({ kind: 'event', event: { data: { id: 'a' } } });
    if (frame.kind === 'event') expect(frame.event.data).not.toHaveProperty('extra');
  });

  it('treats null as absent', () => {
    const frame = parseFrame(
      'alert.created',
      JSON.stringify(envelope('alert.created', { ...alert, routeId: null, resolvedAt: null })),
    );
    expect(frame.kind).toBe('event');
  });

  it('applies defaults to the counters of a job run', () => {
    const frame = parseFrame(
      'job.run',
      JSON.stringify(envelope('job.run', { runId: 'r', kind: 'BATCH_JOB', name: 'n', status: 'STARTED' })),
    );
    expect(frame).toMatchObject({ kind: 'event', event: { data: { readCount: 0, writeCount: 0, skipCount: 0 } } });
  });

  it('skips a type it does not know (SE-11)', () => {
    expect(parseFrame('station.exploded', JSON.stringify(envelope('station.exploded', {})))).toEqual({
      kind: 'unknown',
      type: 'station.exploded',
    });
    expect(parseFrame('constructor', '{"type":"constructor"}').kind).toBe('unknown');
    expect(parseFrame(undefined, '{}').kind).toBe('unknown');
  });

  it('reports a bad payload or bad JSON instead of throwing', () => {
    expect(parseFrame('vehicles.batch', JSON.stringify(envelope('vehicles.batch', { routeId: '18' }))).kind).toBe(
      'invalid',
    );
    expect(parseFrame('alert.created', JSON.stringify(envelope('alert.created', { id: 'a' }))).kind).toBe('invalid');
    expect(parseFrame('dlq.changed', JSON.stringify(envelope('dlq.changed', { kind: 'WEIRD' }))).kind).toBe('invalid');
    expect(parseFrame('heartbeat', 'not json')).toMatchObject({ kind: 'invalid', reason: 'data is not JSON' });
  });
});
