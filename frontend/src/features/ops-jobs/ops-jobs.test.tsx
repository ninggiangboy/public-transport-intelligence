import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getBatchLineage } from '@/api/generated/examples';
import {
  formatOffsets,
  mergeHead,
  resolveBucket,
  resolvePeriod,
  runHref,
  streamStage,
  tabOf,
  totalSeries,
  totalsByBucket,
  triggerLabel,
  type JobRun,
  type JobSummary,
} from '@/features/ops-jobs/model';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { upsertJobRun } from '@/realtime/handlers';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

const copy = pipelineCopy.pipeline;
const MINUTE = 60_000;

const failedReplay: JobRun = {
  runId: 'job:4127',
  kind: 'BATCH_JOB',
  name: 'RawZoneReplayJob',
  status: 'FAILED',
  exitCode: 'FAILED',
  exitMessage: 'Execution became stale',
  startedAt: new Date(Date.now() - 40 * MINUTE).toISOString(),
  endedAt: new Date(Date.now() - 28 * MINUTE).toISOString(),
  durationMs: 729_000,
  readCount: 412_000,
  writeCount: 411_050,
  skipCount: 950,
  jobExecutionId: 4127,
  batchIds: ['0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d'],
  restartable: true,
  request: { type: 'replay', id: '0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a', requestedBy: 'user:operator' },
};
const streamMinute = new Date(Math.floor((Date.now() - 2 * MINUTE) / MINUTE) * MINUTE).toISOString();
const streamRun: JobRun = {
  runId: `stream:gtfs-rt-vehicle-position:${streamMinute.slice(0, 16)}Z`,
  kind: 'STREAM',
  name: 'gtfs-rt-vehicle-position',
  status: 'COMPLETED',
  startedAt: streamMinute,
  endedAt: new Date(Date.parse(streamMinute) + 59_800).toISOString(),
  durationMs: 59_800,
  readCount: 7272,
  writeCount: 7268,
  skipCount: 4,
  duplicateCount: 0,
  batchCount: 60,
};

/** E-31 for the window asked: two sources, a micro-batch every second, none in `gap` (epoch ms). */
function summaryFor(url: URL, gap?: { from: number; to: number }): JobSummary {
  const from = Date.parse(url.searchParams.get('from') ?? '');
  const to = Date.parse(url.searchParams.get('to') ?? '');
  const bucket = url.searchParams.get('bucket') ?? '1m';
  const size = { '1m': MINUTE, '5m': 5 * MINUTE, '15m': 15 * MINUTE, '1h': 60 * MINUTE }[bucket] ?? MINUTE;
  const stream = ['GTFS_RT_VEHICLE_POSITION', 'GTFS_RT_TRIP_UPDATE'].map((source) => {
    const points = [];
    for (let t = Math.floor(from / size) * size; t < to; t += size) {
      const idle = gap !== undefined && t + size > gap.from && t < gap.to;
      const read = idle ? 0 : (size / 1000) * 100;
      points.push({
        bucketStart: new Date(t).toISOString(),
        batches: idle ? 0 : size / 1000,
        failedBatches: 0,
        read,
        written: read - 1,
        skipped: idle ? 0 : 1,
        duplicate: 0,
        p95BatchMs: idle ? 0 : 200,
      });
    }
    return { source, points };
  });
  return {
    bucket,
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    stream,
    batchJobs: { running: 1, completed: 14, failed: 1, stopped: 0 },
  };
}

let listRequests: URL[] = [];
/**
 * E-30 and E-31. Call it after any `{runId}` handler: `server.use` puts the newest first, and `/etl/jobs/{runId}`
 * also matches `/etl/jobs/summary`.
 */
function serve(items: JobRun[] = [failedReplay, streamRun]) {
  server.use(
    http.get(mswPath('/api/v1/etl/jobs/summary'), ({ request }) => HttpResponse.json(summaryFor(new URL(request.url)))),
    http.get(mswPath('/api/v1/etl/jobs'), ({ request }) => {
      const url = new URL(request.url);
      listRequests.push(url);
      const kind = url.searchParams.get('kind');
      return HttpResponse.json({ items: items.filter((run) => !kind || run.kind === kind) });
    }),
  );
}

beforeEach(() => {
  listRequests = [];
  notify.success.mockClear();
  notify.error.mockClear();
  notify.warning.mockClear();
});

function runsTable() {
  return screen.getByRole('table', { name: /^Runs started/ });
}

async function rowOf(text: string) {
  const cell = await within(await screen.findByRole('table', { name: /^Runs started/ })).findByText(text);
  const row = cell.closest('tr');
  if (!row) throw new Error(`no row for ${text}`);
  return row;
}

describe('Pipeline', () => {
  it('AC-1 shows the stages, Read and Written over every source, and a series per source by source', async () => {
    serve();
    await renderRoute('/ops/jobs', { as: 'viewer' });
    expect(await screen.findByRole('heading', { level: 1, name: copy.title })).toBeInTheDocument();
    const stages = screen.getByRole('region', { name: copy.stages.label });
    expect(within(stages).getByText(copy.stages.sources)).toBeInTheDocument();
    // 100 msg/s per source in the last complete minute.
    expect(await within(stages).findAllByText('200')).not.toHaveLength(0);
    const throughput = screen.getByRole('region', { name: copy.throughput.title });
    expect(await within(throughput).findByText(copy.throughput.read)).toBeInTheDocument();
    expect(within(throughput).getByText(copy.throughput.written)).toBeInTheDocument();

    await userEvent.setup().click(within(throughput).getByRole('radio', { name: copy.throughput.bySource }));
    expect(within(throughput).getByText(en.source.GTFS_RT_VEHICLE_POSITION)).toBeInTheDocument();
    expect(within(throughput).getByText(en.source.GTFS_RT_TRIP_UPDATE)).toBeInTheDocument();
    expect(within(throughput).getByRole('radio', { name: copy.throughput.skipped })).toBeInTheDocument();
  });

  it('AC-5 shows a viewer the runs read-only: no Restart, Stop or Run a job', async () => {
    server.use(respond('get', '/api/v1/etl/jobs/{runId}', { ...failedReplay, steps: [], parameters: [] }));
    serve();
    await renderRoute('/ops/jobs?run=job%3A4127', { as: 'viewer' });
    const header = (await screen.findByRole('heading', { level: 1, name: copy.title, hidden: true })).closest('header');
    if (!header) throw new Error('no page header');
    expect(within(header).getByText(copy.readOnly)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: copy.runJob })).not.toBeInTheDocument();
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('Execution became stale', { selector: 'p' })).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: copy.actions.restart })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: copy.actions.stop })).not.toBeInTheDocument();
  });

  it('labels the trigger of each run from its request', async () => {
    serve([failedReplay, { ...failedReplay, runId: 'job:1', jobExecutionId: 1, request: undefined }, streamRun]);
    await renderRoute('/ops/jobs', { as: 'viewer' });
    expect(await rowOf(copy.runNumber(4127))).toHaveTextContent(copy.trigger.replay('operator'));
    expect(await rowOf(copy.runNumber(1))).toHaveTextContent(copy.trigger.schedule);
    expect(await rowOf(streamRun.name)).toHaveTextContent(copy.trigger.streaming);
  });

  it('AC-8 says a failed batch job in the header, on the Batch jobs card and the Failed tab', async () => {
    serve();
    await renderRoute('/ops/jobs', { as: 'viewer' });
    expect(await screen.findByText(copy.failedToday(1), { exact: false })).toBeInTheDocument();
    const card = screen.getByRole('button', { name: copy.stages.goTo(copy.stages.batchJobs) });
    expect(card).toHaveClass('border-tone-danger-border');
    expect(within(card).getByText(copy.stages.failed('1'))).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Failed\s*1$/ })).toBeInTheDocument();
  });

  it('AC-7 reads "Paused" on etl-stream while a consumer pause flag is on', async () => {
    serve();
    server.use(
      respond('get', '/api/v1/etl/flags', {
        items: [
          {
            key: 'etl.consumer.gtfs-rt.paused',
            value: true,
            description: 'Pause',
            updatedBy: 'user:operator',
            updatedAt: '2026-09-29T21:00:00Z',
          },
        ],
      }),
    );
    await renderRoute('/ops/jobs', { as: 'viewer' });
    const card = await screen.findByRole('button', { name: copy.stages.goTo(copy.stages.stream) });
    expect(await within(card).findByText(copy.stages.paused)).toBeInTheDocument();
  });

  it('writes a tab to the URL as a new entry and asks E-30 for that kind', async () => {
    serve();
    const { router } = await renderRoute('/ops/jobs', { as: 'viewer' });
    await rowOf(copy.runNumber(4127));
    await userEvent.setup().click(screen.getByRole('tab', { name: copy.tabs.stream }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ kind: 'STREAM' });
    });
    await waitFor(() => {
      expect(listRequests.at(-1)?.searchParams.get('kind')).toBe('STREAM');
    });
    expect(router.history.length).toBeGreaterThan(1);
    expect(within(runsTable()).queryByText(copy.runNumber(4127))).not.toBeInTheDocument();
  });

  it('AC-2 updates a running row from job.run without reloading', async () => {
    const running: JobRun = {
      ...failedReplay,
      runId: 'job:4131',
      jobExecutionId: 4131,
      name: 'OtpScorecardJob',
      status: 'STARTED',
      endedAt: undefined,
      durationMs: undefined,
      readCount: 100,
      restartable: false,
    };
    serve([running]);
    const { queryClient } = await renderRoute('/ops/jobs', { as: 'viewer' });
    const row = await rowOf(copy.runNumber(4131));
    const before = listRequests.length;
    upsertJobRun(queryClient, { ...running, readCount: 184_000, status: 'COMPLETED' });
    await waitFor(() => {
      expect(row).toHaveTextContent('184K');
    });
    expect(row).toHaveTextContent(en.status.job.COMPLETED);
    expect(listRequests).toHaveLength(before);
  });

  it('AC-4 opens a stream run with its micro-batches and no restart', async () => {
    server.use(
      respond('get', '/api/v1/etl/jobs/{runId}', {
        ...streamRun,
        batches: [
          {
            batchId: '0192f5b0-7c1e-7d3a-9b2c-4e5f6a7b8c9d',
            status: 'COMPLETED',
            writeMode: 'BATCH',
            instanceId: 'etl-stream-1',
            offsets: { 'pti.gtfs-rt.vehicle-position-3': [1200, 1260] },
            recordsRead: 96,
            recordsWritten: 96,
            recordsSkipped: 0,
            recordsDuplicate: 0,
            startedAt: streamMinute,
            finishedAt: new Date(Date.parse(streamMinute) + 212).toISOString(),
            links: { trace: 'http://grafana.test/t', logs: 'http://grafana.test/l' },
          },
        ],
      }),
    );
    serve();
    const { router } = await renderRoute('/ops/jobs', { as: 'operator' });
    await userEvent.setup().click(await rowOf(streamRun.name));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ run: streamRun.runId });
    });
    const drawer = await screen.findByRole('dialog');
    const batches = await within(drawer).findByRole('table', { name: copy.drawer.microBatchesCaption(1) });
    expect(within(batches).getByText('p3: 1200–1260')).toBeInTheDocument();
    expect(within(batches).getByRole('link', { name: '0192f5b0' })).toHaveAttribute(
      'href',
      '/ops/batches/0192f5b0-7c1e-7d3a-9b2c-4e5f6a7b8c9d',
    );
    expect(within(drawer).queryByRole('button', { name: copy.actions.restart })).not.toBeInTheDocument();
  });

  it('AC-3 restarts a failed run after confirming and says which run it became', async () => {
    let restarted = 0;
    server.use(
      respond('get', '/api/v1/etl/jobs/{runId}', { ...failedReplay, steps: [], parameters: [] }),
      http.post(mswPath('/api/v1/etl/jobs/{runId}/restart'), () => {
        restarted += 1;
        return HttpResponse.json(
          {
            id: 'req-1',
            kind: 'RESTART',
            jobName: 'RawZoneReplayJob',
            parameters: {},
            status: 'PENDING',
            requestedBy: 'user:operator',
            requestedAt: new Date().toISOString(),
            targetRunId: 'job:4127',
          },
          { status: 202 },
        );
      }),
      respond('get', '/api/v1/etl/job-requests/{id}', {
        id: 'req-1',
        kind: 'RESTART',
        jobName: 'RawZoneReplayJob',
        parameters: {},
        status: 'RUNNING',
        requestedBy: 'user:operator',
        requestedAt: new Date().toISOString(),
        targetRunId: 'job:4127',
        jobExecutionId: 4130,
        runId: 'job:4130',
      }),
    );
    serve();
    await renderRoute('/ops/jobs?run=job%3A4127', { as: 'operator' });
    const user = userEvent.setup();
    const drawer = await screen.findByRole('dialog');
    await user.click(await within(drawer).findByRole('button', { name: copy.actions.restart }));
    const confirm = await screen.findByRole('dialog', { name: copy.confirm.restartTitle('RawZoneReplayJob', '4127') });
    expect(within(confirm).getByText(copy.confirm.restartBody)).toBeInTheDocument();
    await user.click(within(confirm).getByRole('button', { name: copy.actions.restart }));
    await waitFor(() => {
      expect(notify.success).toHaveBeenCalledWith(copy.feedback.restarted('4130'), expect.anything());
    });
    expect(restarted).toBe(1);
  });

  it('says why a restart was refused with 409', async () => {
    server.use(
      respond('get', '/api/v1/etl/jobs/{runId}', { ...failedReplay, steps: [], parameters: [] }),
      http.post(mswPath('/api/v1/etl/jobs/{runId}/restart'), () =>
        HttpResponse.json(
          {
            type: 'urn:pti:problem:job-not-restartable',
            title: 'Not restartable',
            status: 409,
            detail: 'Only FAILED or STOPPED executions can be restarted.',
            traceId: 't',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    serve();
    await renderRoute('/ops/jobs?run=job%3A4127', { as: 'operator' });
    const user = userEvent.setup();
    await user.click(
      await within(await screen.findByRole('dialog')).findByRole('button', { name: copy.actions.restart }),
    );
    const confirm = await screen.findByRole('dialog', { name: copy.confirm.restartTitle('RawZoneReplayJob', '4127') });
    await user.click(within(confirm).getByRole('button', { name: copy.actions.restart }));
    await waitFor(() => {
      expect(notify.error).toHaveBeenCalledWith('Only FAILED or STOPPED executions can be restarted.');
    });
  });

  it('says a run whose metadata is gone is no longer available', async () => {
    server.use(respondProblem('get', '/api/v1/etl/jobs/{runId}', 404, 'not-found'));
    serve();
    await renderRoute('/ops/jobs?run=job%3A1', { as: 'viewer' });
    expect(await within(await screen.findByRole('dialog')).findByText(copy.drawer.gone)).toBeInTheDocument();
  });

  it('asks anonymous visitors to sign in and calls no ops endpoint', async () => {
    serve();
    await renderRoute('/ops/jobs');
    expect(await screen.findAllByRole('button', { name: en.common.signIn })).not.toHaveLength(0);
    expect(listRequests).toHaveLength(0);
  });
});

describe('Batch lineage', () => {
  it('AC-6 shows where the batch came from, its dead letters and checks, and links back to the run', async () => {
    await renderRoute('/ops/batches/0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d', { as: 'viewer' });
    expect(
      await screen.findByRole('heading', { level: 1, name: pipelineCopy.lineage.title('0192f5a1') }),
    ).toBeInTheDocument();
    const lineage = getBatchLineage.examples.step;
    const back = await screen.findByRole('link', { name: 'job:4127' });
    expect(back.getAttribute('href')).toContain('run=job%3A4127');
    expect(screen.getByText(pipelineCopy.lineage.step)).toBeInTheDocument();
    expect(screen.getByRole('table', { name: pipelineCopy.lineage.byStatusCaption })).toHaveTextContent('938');
    expect(screen.getByRole('table', { name: pipelineCopy.lineage.dqCaption })).toHaveTextContent(
      lineage.dataQuality[0]?.ruleId ?? '',
    );
    expect(screen.getByRole('link', { name: /^Dead letters\s*950$/ })).toHaveAttribute(
      'href',
      expect.stringContaining('/ops/dlq?from='),
    );
  });

  it('says a batch id that is not known is not found', async () => {
    server.use(respondProblem('get', '/api/v1/etl/batches/{batchId}', 404, 'not-found'));
    await renderRoute('/ops/batches/nope', { as: 'viewer' });
    expect(await screen.findByText(pipelineCopy.lineage.notFound)).toBeInTheDocument();
  });
});

describe('model', () => {
  it('reads a period from the URL: a window by default, a fixed range of at most 24 hours', () => {
    expect(resolvePeriod({})).toMatchObject({ kind: 'window', window: '1h' });
    expect(resolvePeriod({ window: '6h' })).toMatchObject({ kind: 'window', window: '6h' });
    expect(resolvePeriod({ from: '2026-09-27T00:00:00Z', to: '2026-09-29T00:00:00Z' })).toEqual({
      kind: 'fixed',
      from: '2026-09-28T00:00:00.000Z',
      to: '2026-09-29T00:00:00.000Z',
      spanMs: 24 * 60 * MINUTE,
    });
    // `from` after `to`: the window instead.
    expect(resolvePeriod({ from: '2026-09-29T00:00:00Z', to: '2026-09-28T00:00:00Z' }).kind).toBe('window');
  });

  it('picks the bucket by span and raises one that E-31 would refuse', () => {
    expect(resolveBucket(undefined, 60 * MINUTE)).toBe('1m');
    expect(resolveBucket(undefined, 6 * 60 * MINUTE)).toBe('5m');
    expect(resolveBucket(undefined, 24 * 60 * MINUTE)).toBe('15m');
    expect(resolveBucket('1m', 24 * 60 * MINUTE)).toBe('1m');
    expect(resolveBucket('1m', 25 * 60 * MINUTE)).toBe('5m');
  });

  it('takes the stage numbers from the last complete minute, and shows idle buckets as gaps', () => {
    const to = Date.parse('2026-09-29T21:20:30Z');
    const url = new URL('http://x/s?bucket=1m');
    url.searchParams.set('from', new Date(to - 15 * MINUTE).toISOString());
    url.searchParams.set('to', new Date(to).toISOString());
    const summary = summaryFor(url, { from: to - 10 * MINUTE, to: to - 8 * MINUTE });
    const stage = streamStage(summary);
    expect(stage.readPerSecond).toBe(200);
    expect(stage.batchesPerMinute).toBe(120);
    expect(stage.p95Ms).toBe(200);
    const read = totalSeries(totalsByBucket(summary), 'read');
    expect(read.filter((p) => p.v === null).length).toBeGreaterThan(0);
    expect(read.at(-1)?.v).toBe(12_000);
  });

  it('labels triggers, offsets and tabs', () => {
    expect(triggerLabel({ kind: 'STREAM' })).toBe(copy.trigger.streaming);
    expect(triggerLabel({ kind: 'BATCH_JOB' })).toBe(copy.trigger.schedule);
    expect(triggerLabel({ kind: 'BATCH_JOB', request: { type: 'job', requestedBy: 'user:linh' } })).toBe(
      copy.trigger.manual('linh'),
    );
    expect(formatOffsets({ 'topic-0': [1, 9], 'topic-12': [5, 7] })).toBe('p0: 1–9, p12: 5–7');
    expect(tabOf({ status: ['FAILED'] })).toBe('failed');
    expect(tabOf({ status: ['FAILED', 'STOPPED'] })).toBe('all');
    expect(tabOf({ kind: 'STREAM', status: ['FAILED'] })).toBe('stream');
  });

  it('merges a fresh first page over the loaded ones, newest first', () => {
    const page = (items: JobRun[]) => ({ data: { items }, asOf: undefined });
    const merged = mergeHead({ pages: [page([streamRun]), page([failedReplay])], pageParams: [undefined, 'c'] }, [
      { ...streamRun, readCount: 1 },
      { ...streamRun, runId: 'stream:new', startedAt: new Date().toISOString() },
      { ...failedReplay, status: 'STOPPED' },
    ]);
    expect(merged?.pages[0]?.data.items.map((run) => run.runId)).toEqual(['stream:new', streamRun.runId]);
    expect(merged?.pages[0]?.data.items[1]?.readCount).toBe(1);
    expect(merged?.pages[1]?.data.items[0]?.status).toBe('STOPPED');
  });

  it('links a batch back to its run inside a range around its start', () => {
    expect(runHref('job:1', '2026-09-29T20:40:00Z')).toBe(
      '/ops/jobs?run=job%3A1&from=2026-09-29T20%3A10%3A00.000Z&to=2026-09-29T21%3A10%3A00.000Z',
    );
  });
});
