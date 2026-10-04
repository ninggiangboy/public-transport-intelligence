import { describe, expect, it } from 'vitest';

import { getFreshness } from '@/api/generated/examples';
import type { Freshness } from '@/app/freshness';
import { bannerCondition } from '@/app/shell/stale-banner';
import type { RealtimeState } from '@/realtime/useRealtime';

const fresh = getFreshness.examples.fresh;
const open: RealtimeState = { status: 'open' };
const ok: Freshness = { data: fresh, failed: false, updatedAt: 1_000 };

const staleFreshness: Freshness = {
  failed: false,
  data: {
    ...fresh,
    stale: true,
    sources: [
      {
        source: 'GTFS_RT_VEHICLE_POSITION',
        lastEventAt: '2026-09-29T21:17:00Z',
        ageSeconds: 155,
        staleAfterSeconds: 120,
        stale: true,
      },
      {
        source: 'GTFS_RT_TRIP_UPDATE',
        lastEventAt: '2026-09-29T21:09:00Z',
        ageSeconds: 635,
        staleAfterSeconds: 120,
        stale: true,
      },
      {
        source: 'TICKETING_SALES',
        lastEventAt: '2026-09-29T19:00:00Z',
        ageSeconds: 8000,
        staleAfterSeconds: 900,
        stale: true,
      },
    ],
  },
};

function condition(overrides: Partial<Parameters<typeof bannerCondition>[0]>) {
  return bannerCondition({ online: true, freshness: ok, realtime: open, reconnectingLong: false, ...overrides });
}

describe('bannerCondition (DOC-37 §2.4)', () => {
  it('shows nothing when everything is fine', () => {
    expect(condition({})).toBeUndefined();
  });

  it('CP-08 shows only offline when offline and stale at once', () => {
    expect(
      condition({
        online: false,
        freshness: staleFreshness,
        realtime: { status: 'open', lastEventAt: new Date(5_000) },
      }),
    ).toEqual({ kind: 'offline', lastDataAt: 5_000 });
  });

  it('prefers "freshness unknown" to stale when E-60 fails or its probe does', () => {
    expect(condition({ freshness: { ...staleFreshness, failed: true } })).toEqual({ kind: 'freshnessUnknown' });
    expect(condition({ freshness: { failed: false, data: { ...fresh, probeError: true } } })).toEqual({
      kind: 'freshnessUnknown',
    });
  });

  it('reports the older of the two GTFS-realtime sources, never ticketing', () => {
    expect(condition({ freshness: staleFreshness })).toEqual({
      kind: 'stale',
      source: 'GTFS_RT_TRIP_UPDATE',
      ageSeconds: 635,
    });
  });

  it('says no live data has arrived when vehicle positions never did', () => {
    const data = {
      ...fresh,
      stale: true,
      sources: [{ source: 'GTFS_RT_VEHICLE_POSITION', stale: true, staleAfterSeconds: 120 }],
    };
    expect(condition({ freshness: { failed: false, data } })).toEqual({ kind: 'staleNoData' });
  });

  it('puts stale data above polling, and polling above reconnecting', () => {
    expect(condition({ freshness: staleFreshness, realtime: { status: 'polling' } })?.kind).toBe('stale');
    expect(condition({ realtime: { status: 'polling', pollPeriodMs: 5_000 } })).toEqual({
      kind: 'polling',
      seconds: 5,
    });
  });

  it('mentions reconnecting only after 5 s of it', () => {
    expect(condition({ realtime: { status: 'reconnecting' } })).toBeUndefined();
    expect(condition({ realtime: { status: 'reconnecting' }, reconnectingLong: true })).toEqual({
      kind: 'reconnecting',
    });
  });
});
