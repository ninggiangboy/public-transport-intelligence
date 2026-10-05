import { describe, expect, it } from 'vitest';

import { keys, normalizeFilters, normalizeList } from '@/api/keys';

describe('query keys (DOC-26 §9)', () => {
  it('builds the shapes the real-time handlers rely on', () => {
    expect(keys.vehicles.live(['18', '2'])).toEqual(['vehicles', 'live', ['18', '2'].sort()]);
    expect(keys.vehicles.live()).toEqual(['vehicles', 'live', []]);
    expect(keys.alerts.list({ severity: [3, 1] })).toEqual(['alerts', 'list', { severity: [1, 3] }]);
    expect(keys.etl.jobs.list({ kind: 'BATCH_JOB' })).toEqual(['etl', 'jobs', 'list', { kind: 'BATCH_JOB' }]);
    expect(keys.etl.job('job:1')).toEqual(['etl', 'job', 'job:1']);
    expect(keys.etl.dlq.list({ status: ['NEW'] })).toEqual(['etl', 'dlq', 'list', { status: ['NEW'] }]);
    expect(keys.etl.dlq.summary()).toEqual(['etl', 'dlq', 'summary']);
    expect(keys.etl.dlq.detail('d1')).toEqual(['etl', 'dlq', 'detail', 'd1']);
    expect(keys.insights.bunching()).toEqual(['insights', 'bunching', {}]);
    expect(keys.insights.dispatch()[1]).toBe('dispatch');
    expect(keys.insights.disruption()[1]).toBe('disruption');
    expect(keys.stops.detail('51420')).toEqual(['stops', '51420', 'detail']);
    expect(keys.insights.otp({ to: '2026-09-28', from: '2026-09-22' })).toEqual([
      'insights',
      'otp',
      { from: '2026-09-22', to: '2026-09-28' },
    ]);
    expect(keys.routes.delays('18', { bucket: 'day', directionId: undefined })).toEqual([
      'routes',
      '18',
      'delays',
      { bucket: 'day' },
    ]);
  });

  it('gives equal filters one key', () => {
    expect(keys.vehicles.live(['b', 'a', 'a'])).toEqual(keys.vehicles.live(['a', 'b']));
    expect(keys.alerts.list({ routeId: ['2', '1'], type: undefined, state: 'open' })).toEqual(
      keys.alerts.list({ state: 'open', routeId: ['1', '2'], severity: [] }),
    );
  });

  it('normalises lists and filters', () => {
    expect(normalizeList(['b', 'a', 'b'])).toEqual(['a', 'b']);
    expect(normalizeFilters(undefined)).toEqual({});
    expect(Object.keys(normalizeFilters({ z: 1, a: true }))).toEqual(['a', 'z']);
  });
});
