import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { components } from '@/api/generated/schema';
import { replayCopy as copy } from '@/i18n/replay';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';
import { toLocalInput } from '@/lib/time';

type Replay = components['schemas']['ReplayResponse'];

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const ZONE = 'America/Chicago';

const done: Replay = {
  id: '0192f5b2-0000-7000-8000-000000000001',
  kind: 'RAW_RANGE',
  source: 'GTFS_RT_TRIP_UPDATE',
  fromTs: '2026-09-28T05:00:00Z',
  toTs: '2026-09-29T05:00:00Z',
  recomputeAnalytics: true,
  status: 'DONE',
  requestedBy: 'user:operator',
  requestedAt: '2026-09-29T20:39:58Z',
  startedAt: '2026-09-29T20:40:02Z',
  finishedAt: '2026-09-29T20:50:14Z',
  runId: 'job:4127',
  jobExecutionId: 4127,
  stats: {
    objects: 288,
    linesRead: 1204332,
    filtered: 51200,
    processed: 1153132,
    written: 1150020,
    duplicate: 0,
    skipped: 3112,
    dlqInserted: 12,
    dlqUpdated: 3100,
    dlqResolved: 7,
    minEventTs: '2026-09-28T05:00:03Z',
    maxEventTs: '2026-09-29T04:59:58Z',
    analyticsRecomputed: true,
    analytics: { BUNCHING: { scopes: 37, upserted: 12, deleted: 1 } },
  },
};

const failed: Replay = {
  ...done,
  id: '0192f5b2-0000-7000-8000-000000000002',
  status: 'FAILED',
  source: 'TICKETING_SALES',
  message: 'Execution became stale',
  stats: undefined,
  runId: 'job:4128',
  jobExecutionId: 4128,
};

/** E-31 hourly over the last 24 hours for trip updates, as the source card and the bars read it. */
function hourlySummary() {
  const to = Math.floor(Date.now() / HOUR) * HOUR + HOUR;
  const points = Array.from({ length: 24 }, (_, index) => ({
    bucketStart: new Date(to - (24 - index) * HOUR).toISOString(),
    batches: 60,
    duplicate: 0,
    failedBatches: 0,
    p95BatchMs: 200,
    read: 100_000 + index,
    skipped: 0,
    written: 100_000,
  }));
  return {
    batchJobs: { completed: 1, failed: 0, running: 0, stopped: 0 },
    bucket: '1h',
    from: new Date(to - 24 * HOUR).toISOString(),
    to: new Date(to).toISOString(),
    stream: [{ source: 'GTFS_RT_TRIP_UPDATE', points }],
  };
}

beforeEach(() => {
  server.use(respond('get', '/api/v1/etl/jobs/summary', hourlySummary()));
});

/** The form waits for the agency zone of E-60 before typing times into it. */
async function openForm(path = '/ops/replay') {
  const result = await renderRoute(path, { as: 'operator' });
  const form = await screen.findByRole('form', { name: copy.form.label });
  await waitFor(() => {
    expect(within(form).getByText(/^C[DS]T$/)).toBeInTheDocument();
  });
  return { ...result, form };
}

describe('Replay (DOC-36 screens/ops-console-replay)', () => {
  it('shows a viewer the history only, read-only (AC-6)', async () => {
    const { router } = await renderRoute('/ops/replay?tab=new', { as: 'viewer' });
    expect(await screen.findByRole('heading', { level: 1, name: copy.heading })).toBeInTheDocument();
    expect(screen.getAllByText(copy.readOnly)[0]).toBeInTheDocument();
    const tabs = screen.getByRole('tablist', { name: copy.tabs.label });
    expect(
      within(tabs)
        .getAllByRole('tab')
        .map((tab) => tab.textContent),
    ).toEqual([copy.tabs.history]);
    expect(screen.queryByRole('form', { name: copy.form.label })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ tab: 'history' });
    });
    expect(await screen.findByRole('table', { name: /^Replays of the last 7 days/ })).toBeInTheDocument();
  });

  it('estimates a valid range and fills the hourly bars of the range (AC-1, AC-7)', async () => {
    const user = userEvent.setup();
    const asked = vi.fn();
    server.use(
      http.get(mswPath('/api/v1/etl/replays/estimate'), ({ request }) => {
        asked(Object.fromEntries(new URL(request.url).searchParams));
        return HttpResponse.json({
          source: 'GTFS_RT_TRIP_UPDATE',
          fromTs: 'a',
          toTs: 'b',
          estimatedMessages: 1_152_000,
          estimatedDurationSeconds: 384,
          basis: 'STREAM_BATCH_LOG',
          coverage: 0.8,
          warnings: ['Some minutes in this range have no processing history; the estimate may be low.'],
        });
      }),
    );
    const { form } = await openForm();

    await user.click(within(form).getByRole('radio', { name: 'Trip updates' }));
    expect(await within(form).findByText(copy.form.perDay('2.4M'))).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: copy.form.presets.last24h }));

    const card = screen.getByRole('region', { name: copy.estimate.title });
    expect(await within(card).findByText(copy.estimate.messages('1.2M'), {}, { timeout: 1_500 })).toBeInTheDocument();
    expect(within(card).getByText(copy.estimate.coverage)).toBeInTheDocument();
    expect(within(card).getByText('80%')).toBeInTheDocument();
    expect(within(card).getByText(copy.estimate.about('6 min'))).toBeInTheDocument();
    expect(
      within(card).getByText('Some minutes in this range have no processing history; the estimate may be low.'),
    ).toBeInTheDocument();
    expect(asked).toHaveBeenCalledTimes(1);
    expect(asked.mock.calls[0]?.[0]).toMatchObject({ source: 'GTFS_RT_TRIP_UPDATE' });

    // The range ends 10 minutes ago, so the bars of the last 24 hours are filled but the newest partial hour.
    const bars = within(screen.getByRole('list', { name: copy.form.perHour })).getAllByRole('listitem');
    expect(bars).toHaveLength(24);
    expect(bars.filter((bar) => bar.textContent.endsWith('in the selected range')).length).toBeGreaterThanOrEqual(23);
    expect(within(card).getByRole('button', { name: copy.form.start })).toBeEnabled();
  });

  it('refuses an end less than 10 minutes ago without calling the API (AC-2)', async () => {
    const user = userEvent.setup();
    const created = vi.fn();
    server.use(
      http.post(mswPath('/api/v1/etl/replays'), () => {
        created();
        return HttpResponse.json({});
      }),
    );
    const { form } = await openForm();
    await user.click(within(form).getByRole('radio', { name: 'Trip updates' }));
    fireEvent.change(within(form).getByLabelText(copy.form.from), {
      target: { value: toLocalInput(Date.now() - HOUR, ZONE) },
    });
    fireEvent.change(within(form).getByLabelText(copy.form.to), {
      target: { value: toLocalInput(Date.now() - 5 * MINUTE, ZONE) },
    });

    expect(within(form).getByText(copy.errors.settle)).toBeInTheDocument();
    expect(within(form).getByLabelText(copy.form.to)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: copy.form.start })).toBeDisabled();
    expect(created).not.toHaveBeenCalled();

    fireEvent.change(within(form).getByLabelText(copy.form.from), {
      target: { value: toLocalInput(Date.now() - 30 * 24 * HOUR, ZONE) },
    });
    expect(within(form).getByText(copy.errors.maxAge)).toBeInTheDocument();
  });

  it('starts a replay with an Idempotency-Key and opens it in the history (AC-3)', async () => {
    const user = userEvent.setup();
    const sent = vi.fn();
    server.use(
      http.post(mswPath('/api/v1/etl/replays'), async ({ request }) => {
        sent({ body: await request.json(), key: request.headers.get('Idempotency-Key') });
        return HttpResponse.json({ ...done, id: 'r-new', status: 'PENDING', stats: undefined }, { status: 202 });
      }),
      respond('get', '/api/v1/etl/replays/{id}', { ...done, id: 'r-new', status: 'PENDING', stats: undefined }),
    );
    const { form, router } = await openForm();
    await user.click(within(form).getByRole('radio', { name: 'Trip updates' }));
    await user.click(within(form).getByRole('button', { name: copy.form.presets.lastHour }));
    await user.click(within(form).getByRole('switch', { name: copy.form.recompute }));
    const start = screen.getByRole('button', { name: copy.form.start });
    await waitFor(() => {
      expect(start).toBeEnabled();
    });
    await user.click(start);

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/^Replay Trip updates from /)).toBeInTheDocument();
    expect(within(dialog).queryByText(copy.dialog.recompute)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: copy.dialog.confirm }));

    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ tab: 'history', replay: 'r-new' });
    });
    expect(sent).toHaveBeenCalledTimes(1);
    const call = sent.mock.calls[0]?.[0] as { body: Record<string, unknown>; key: string | null };
    expect(call.body).toMatchObject({ source: 'GTFS_RT_TRIP_UPDATE', recomputeAnalytics: false });
    expect(Date.parse(String(call.body.toTs)) - Date.parse(String(call.body.fromTs))).toBe(HOUR);
    expect(call.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(notify.success).toHaveBeenCalledWith(copy.feedback.sent, expect.anything());
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('Queued')).toBeInTheDocument();
  });

  it('turns a second replay of the source into a link to the running one (AC-4)', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(mswPath('/api/v1/etl/replays'), () =>
        HttpResponse.json(
          {
            type: 'urn:pti:problem:replay-already-running',
            title: 'Replay already running',
            status: 409,
            traceId: 't',
            existingReplayId: 'r-running',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
      respond('get', '/api/v1/etl/replays/{id}', { ...done, id: 'r-running', status: 'RUNNING', stats: undefined }),
    );
    const { form, router } = await openForm();
    await user.click(within(form).getByRole('radio', { name: 'Trip updates' }));
    await user.click(within(form).getByRole('button', { name: copy.form.presets.last24h }));
    const start = screen.getByRole('button', { name: copy.form.start });
    await waitFor(() => {
      expect(start).toBeEnabled();
    });
    await user.click(start);
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: copy.dialog.confirm }));

    const conflict = await screen.findByRole('dialog', { name: copy.dialog.conflictTitle });
    await user.click(within(conflict).getByRole('button', { name: copy.dialog.viewRunning }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ tab: 'history', replay: 'r-running' });
    });
  });

  it('pins a refused window to its field (422 replay-window-invalid)', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(mswPath('/api/v1/etl/replays'), () =>
        HttpResponse.json(
          {
            type: 'urn:pti:problem:replay-window-invalid',
            title: 'Invalid window',
            status: 422,
            traceId: 't',
            errors: [{ field: 'fromTs', message: 'must not be more than 29 days ago' }],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const { form } = await openForm();
    await user.click(within(form).getByRole('radio', { name: 'Ticket sales' }));
    await user.click(within(form).getByRole('button', { name: copy.form.presets.yesterday }));
    const start = screen.getByRole('button', { name: copy.form.start });
    await waitFor(() => {
      expect(start).toBeEnabled();
    });
    await user.click(start);
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: copy.dialog.confirm }));
    expect(await within(form).findByText('must not be more than 29 days ago')).toBeInTheDocument();
  });

  it('shows the estimate as unavailable and still lets the replay start', async () => {
    const user = userEvent.setup();
    server.use(
      respond('get', '/api/v1/etl/replays/estimate', {
        source: 'GTFS_RT_VEHICLE_POSITION',
        fromTs: 'a',
        toTs: 'b',
        basis: 'UNAVAILABLE',
        coverage: 0,
        warnings: ['No processing history for this range.'],
      }),
    );
    const { form } = await openForm();
    await user.click(within(form).getByRole('radio', { name: 'Vehicle positions' }));
    await user.click(within(form).getByRole('button', { name: copy.form.presets.lastHour }));
    const card = screen.getByRole('region', { name: copy.estimate.title });
    expect(await within(card).findByText(copy.estimate.unavailable)).toBeInTheDocument();
    expect(within(card).getByText('No processing history for this range.')).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: copy.form.start })).toBeEnabled();
  });

  it('prefills the form from the URL once (§2)', async () => {
    const { form } = await openForm(
      '/ops/replay?source=TICKETING_SALES&from=2026-09-28T05%3A00%3A00Z&to=2026-09-29T05%3A00%3A00Z&recompute=0',
    );
    expect(within(form).getByRole('radio', { name: 'Ticket sales' })).toBeChecked();
    expect(within(form).getByLabelText(copy.form.from)).toHaveValue('2026-09-28T00:00');
    expect(within(form).getByLabelText(copy.form.to)).toHaveValue('2026-09-29T00:00');
    expect(within(form).getByRole('switch', { name: copy.form.recompute })).toHaveAttribute('aria-checked', 'false');
  });

  it('lists the history and opens a finished replay with its statistics and run (AC-5)', async () => {
    const user = userEvent.setup();
    server.use(
      respond('get', '/api/v1/etl/replays', { items: [done, failed] }),
      http.get(mswPath('/api/v1/etl/replays/:id'), ({ params }) =>
        HttpResponse.json(params.id === done.id ? done : failed),
      ),
    );
    const { router } = await renderRoute('/ops/replay?tab=history', { as: 'operator' });
    const table = await screen.findByRole('table', { name: /^Replays of the last 7 days/ });
    expect(within(table).getByText('Execution became stale')).toBeInTheDocument();
    expect(within(table).getByText(copy.history.took('10 min 12 s'))).toBeInTheDocument();

    await user.click(within(table).getByText('Trip updates'));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ replay: done.id });
    });
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByRole('table', { name: copy.drawer.statsCaption })).toBeInTheDocument();
    expect(within(drawer).getByText(copy.drawer.stat.linesRead)).toBeInTheDocument();
    expect(within(drawer).getByText('1,204,332')).toBeInTheDocument();
    expect(within(drawer).getByText(copy.drawer.detector('BUNCHING', '37', '12', '1'))).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: `${copy.drawer.run('4127')} →` })).toHaveAttribute(
      'href',
      '/ops/jobs?run=job%3A4127',
    );
    expect(within(drawer).getByRole('link', { name: copy.drawer.viewResolved })).toHaveAttribute(
      'href',
      expect.stringContaining('/ops/dlq?status=RESOLVED&source=GTFS_RT_TRIP_UPDATE'),
    );

    // "Replay again" opens the form filled with the same source and range.
    await user.click(within(drawer).getByRole('button', { name: copy.drawer.replayAgain }));
    const form = await screen.findByRole('form', { name: copy.form.label });
    expect(within(form).getByRole('radio', { name: 'Trip updates' })).toBeChecked();
    expect(router.state.location.search).toMatchObject({ tab: 'new', source: 'GTFS_RT_TRIP_UPDATE' });
  });

  it('offers an operator "Restart run" on a failed replay', async () => {
    const user = userEvent.setup();
    const restarted = vi.fn();
    server.use(
      respond('get', '/api/v1/etl/replays/{id}', failed),
      http.post(mswPath('/api/v1/etl/jobs/:runId/restart'), ({ params }) => {
        restarted(params.runId);
        return HttpResponse.json(
          {
            id: 'req-1',
            kind: 'RESTART',
            jobName: 'RawZoneReplayJob',
            parameters: {},
            status: 'PENDING',
            requestedBy: 'user:operator',
            requestedAt: '2026-09-29T21:26:00Z',
            targetRunId: 'job:4128',
          },
          { status: 202 },
        );
      }),
    );
    await renderRoute(`/ops/replay?tab=history&replay=${failed.id}`, { as: 'operator' });
    const drawer = await screen.findByRole('dialog');
    await user.click(await within(drawer).findByRole('button', { name: copy.drawer.restart }));
    const confirm = await screen.findByRole('dialog', { name: /Restart/ });
    await user.click(within(confirm).getByRole('button', { name: copy.drawer.restart }));
    await waitFor(() => {
      expect(restarted).toHaveBeenCalledWith('job:4128');
    });
  });

  it('says when the replay no longer exists', async () => {
    server.use(respondProblem('get', '/api/v1/etl/replays/{id}', 404, 'not-found'));
    await renderRoute('/ops/replay?tab=history&replay=gone', { as: 'viewer' });
    expect(await screen.findByText(copy.drawer.gone)).toBeInTheDocument();
  });

  it('counts the running replays on the History tab and shows the empty history', async () => {
    server.use(
      http.get(mswPath('/api/v1/etl/replays'), ({ request }) => {
        const url = new URL(request.url);
        return HttpResponse.json({
          items: url.searchParams.get('limit') === '10' ? [{ ...done, id: 'r1', status: 'RUNNING' }] : [],
        });
      }),
    );
    await renderRoute('/ops/replay?tab=history', { as: 'viewer' });
    expect(await screen.findByText(copy.history.empty)).toBeInTheDocument();
    expect(await screen.findByText(copy.running(1))).toBeInTheDocument();
  });
});
