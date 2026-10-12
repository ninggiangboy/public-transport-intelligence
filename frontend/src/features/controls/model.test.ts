import { describe, expect, it } from 'vitest';

import {
  checkJob,
  emptyForm,
  fieldOfApiError,
  lastProcessed,
  longFor,
  requestPollMs,
  validSourceUri,
  type JobClock,
  type JobForm,
} from '@/features/controls/model';
import { controlsCopy } from '@/i18n/controls';

const e = controlsCopy.job.error;
// 4:19 PM CDT on Tue, Sep 29 2026.
const clock: JobClock = { now: Date.parse('2026-09-29T21:19:35Z'), timeZone: 'America/Chicago' };
const form = (patch: Partial<JobForm>): JobForm => ({ ...emptyForm(patch.job ?? 'OtpScorecardJob'), ...patch });

describe('Run a job: client checks of E-33 (§6.1)', () => {
  it('sends yesterday by default and refuses today (AC-6)', () => {
    expect(checkJob(form({}), clock)).toEqual({ errors: {}, parameters: { serviceDates: ['2026-09-28'] } });
    expect(checkJob(form({ serviceDates: ['2026-09-28', '2026-09-29'] }), clock).errors).toEqual({
      serviceDates: e.beforeToday,
    });
    expect(checkJob(form({ serviceDates: [] }), clock).errors).toEqual({ serviceDates: e.dateCount });
    const many = Array.from({ length: 32 }, (_, i) => `2026-08-${String(i + 1).padStart(2, '0')}`);
    expect(checkJob(form({ serviceDates: many }), clock).errors).toEqual({ serviceDates: e.dateCount });
  });

  it('checks the hour of EtaAggregationJob on the business clock', () => {
    const eta = (hour: string) => checkJob(form({ job: 'EtaAggregationJob', hour }), clock);
    expect(eta('')).toEqual({ errors: {}, parameters: { force: false } });
    expect(eta('2026-09-29T15:00')).toEqual({
      errors: {},
      parameters: { hour: '2026-09-29T20:00:00.000Z', force: false },
    });
    expect(eta('2026-09-29T15:30').errors).toEqual({ hour: e.hourStart });
    expect(eta('2026-09-29T17:00').errors).toEqual({ hour: e.future });
  });

  it('checks the period and detectors of AnalyticsRecomputeJob', () => {
    const recompute = (patch: Partial<JobForm>) => checkJob(form({ job: 'AnalyticsRecomputeJob', ...patch }), clock);
    expect(recompute({ from: '2026-09-28T00:00', to: '2026-09-29T00:00' })).toEqual({
      errors: {},
      parameters: {
        detectors: ['BUNCHING', 'DISRUPTION', 'TICKETING'],
        fromTs: '2026-09-28T05:00:00.000Z',
        toTs: '2026-09-29T05:00:00.000Z',
      },
    });
    // Incomplete: no error, nothing to send.
    expect(recompute({ from: '2026-09-28T00:00' })).toEqual({ errors: {} });
    expect(recompute({ from: '2026-09-29T00:00', to: '2026-09-28T00:00' }).errors).toEqual({ toTs: e.order });
    expect(recompute({ from: '2026-09-20T00:00', to: '2026-09-28T00:00' }).errors).toEqual({ toTs: e.maxRange });
    expect(recompute({ from: '2026-09-29T00:00', to: '2026-09-29T18:00' }).errors).toEqual({ toTs: e.future });
    expect(recompute({ from: '2026-09-28T00:00', to: '2026-09-29T00:00', detectors: [] }).errors).toEqual({
      detectors: e.detectors,
    });
    expect(
      recompute({ from: '2026-09-28T00:00', to: '2026-09-29T00:00', detectors: ['TICKETING', 'BUNCHING'] }).parameters,
    ).toMatchObject({ detectors: ['BUNCHING', 'TICKETING'] });
  });

  it('accepts https, s3 and file URIs for GtfsStaticLoadJob', () => {
    expect(validSourceUri('https://example.org/gtfs.zip')).toBe(true);
    expect(validSourceUri('s3://raw/gtfs-static/9f2c.zip')).toBe(true);
    expect(validSourceUri('file:///data/gtfs.zip')).toBe(true);
    expect(validSourceUri('http://example.org/gtfs.zip')).toBe(false);
    expect(validSourceUri('gtfs.zip')).toBe(false);
    const load = (sourceUri: string) => checkJob(form({ job: 'GtfsStaticLoadJob', sourceUri }), clock);
    expect(load('')).toEqual({ errors: {}, parameters: { allowReactivate: false } });
    expect(load('ftp://x').errors).toEqual({ sourceUri: e.uri });
    expect(load(' s3://raw/a.zip ').parameters).toEqual({ sourceUri: 's3://raw/a.zip', allowReactivate: false });
  });

  it('sends no parameters for PartitionMaintenanceJob', () => {
    expect(checkJob(form({ job: 'PartitionMaintenanceJob' }), clock)).toEqual({ errors: {}, parameters: {} });
  });

  it('maps the fields of a 400 onto the form', () => {
    expect(fieldOfApiError('parameters.serviceDates')).toBe('serviceDates');
    expect(fieldOfApiError('parameters.toTs')).toBe('toTs');
    expect(fieldOfApiError('jobName')).toBeUndefined();
  });
});

describe('flags and listeners', () => {
  it('marks a state older than an hour', () => {
    const now = Date.parse('2026-09-29T21:00:00Z');
    expect(longFor({ updatedAt: '2026-09-29T20:30:00Z' }, now)).toBeUndefined();
    expect(longFor({ updatedAt: '2026-09-29T19:30:00Z' }, now)).toBe(90 * 60_000);
  });

  it('finds the newest minute with a micro-batch', () => {
    const point = (bucketStart: string, batches: number) => ({
      bucketStart,
      batches,
      read: 1,
      written: 1,
      skipped: 0,
      duplicate: 0,
      failedBatches: 0,
      p95BatchMs: 1,
    });
    const summary = {
      bucket: '1m',
      from: 'a',
      to: 'b',
      batchJobs: { completed: 0, failed: 0, running: 0, stopped: 0 },
      stream: [
        {
          source: 'GTFS_RT_TRIP_UPDATE',
          points: [
            point('2026-09-29T21:00:00Z', 3),
            point('2026-09-29T21:02:00Z', 2),
            point('2026-09-29T21:03:00Z', 0),
          ],
        },
      ],
    };
    expect(lastProcessed(summary, 'GTFS_RT_TRIP_UPDATE')).toBe('2026-09-29T21:02:00Z');
    expect(lastProcessed(summary, 'TICKETING_SALES')).toBeUndefined();
  });

  it('polls a request every 2 s until picked up, every 10 s while it runs', () => {
    expect(requestPollMs(undefined)).toBe(2_000);
    expect(requestPollMs('PENDING')).toBe(2_000);
    expect(requestPollMs('RUNNING')).toBe(10_000);
    expect(requestPollMs('DONE')).toBe(false);
    expect(requestPollMs('REJECTED')).toBe(false);
  });
});
