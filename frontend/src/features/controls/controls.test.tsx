import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { components } from '@/api/generated/schema';
import { recentRequests } from '@/features/controls/requests-store';
import { controlsCopy } from '@/i18n/controls';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

type Flag = components['schemas']['RuntimeFlagResponse'];

const copy = controlsCopy;
const HOUR = 3_600_000;
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

function flag(key: string, value: boolean, patch: Partial<Flag> = {}): Flag {
  return { key, value, description: key, updatedBy: 'migration', updatedAt: ago(5 * 60_000), ...patch };
}

function flags(patch: Partial<Record<string, Partial<Flag>>> = {}): Flag[] {
  return [
    flag('etl.consumer.gtfs-rt.paused', false, patch['etl.consumer.gtfs-rt.paused']),
    flag('etl.consumer.ticketing.paused', false, patch['etl.consumer.ticketing.paused']),
    flag('triage.dlq.enabled', true, patch['triage.dlq.enabled']),
    flag('triage.auto-replay.enabled', true, patch['triage.auto-replay.enabled']),
    flag('triage.ticketing.enabled', true, patch['triage.ticketing.enabled']),
    flag('triage.disruption.enabled', true, patch['triage.disruption.enabled']),
    flag('triage.dispatch.enabled', true, patch['triage.dispatch.enabled']),
  ];
}

function serveFlags(items: Flag[]) {
  server.use(respond('get', '/api/v1/etl/flags', { items }));
}

afterEach(() => {
  recentRequests.clear();
  vi.clearAllMocks();
});

async function openControls(as: 'viewer' | 'operator', path = '/ops/controls') {
  const result = await renderRoute(path, { as });
  await screen.findByRole('heading', { level: 1, name: copy.title });
  return result;
}

const gtfsSwitch = () => screen.findByRole('switch', { name: copy.consumer.gtfsRt.name });

describe('Controls: flags', () => {
  it('shows a viewer every block with the switches disabled and no actions (AC-4)', async () => {
    serveFlags(flags());
    await openControls('viewer');
    expect(within(screen.getByRole('main')).getByText(copy.readOnly)).toBeInTheDocument();
    const switches = await screen.findAllByRole('switch');
    expect(switches).toHaveLength(7);
    for (const control of switches) expect(control).toBeDisabled();
    expect(screen.queryByRole('button', { name: copy.job.run })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: copy.feed.load })).not.toBeInTheDocument();
    for (const section of Object.values(copy.sections)) {
      expect(screen.getByRole('heading', { name: section })).toBeInTheDocument();
    }
  });

  it('says who paused a consumer and for how long (AC-2, AC-3)', async () => {
    serveFlags(
      flags({
        'etl.consumer.gtfs-rt.paused': { value: true, updatedBy: 'user:operator', updatedAt: ago(2 * HOUR + 60_000) },
        'triage.dispatch.enabled': { value: false, updatedBy: 'user:operator', updatedAt: ago(3 * HOUR) },
      }),
    );
    await openControls('viewer');
    expect(await gtfsSwitch()).toHaveAttribute('aria-checked', 'false');
    const pipeline = screen.getByRole('region', { name: copy.sections.pipeline });
    expect(within(pipeline).getByText(copy.consumer.paused)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`^Paused by operator 2 h ago\\.`))).toBeInTheDocument();
    expect(screen.getByText(copy.consumer.pausedFor('2 h 1 min'))).toBeInTheDocument();
    expect(screen.getByText(copy.flag.offFor('3 h'))).toBeInTheDocument();
    // The ticketing consumer runs and has no callout.
    expect(screen.getByRole('switch', { name: copy.consumer.ticketing.name })).toHaveAttribute('aria-checked', 'true');
  });

  it('pauses after a confirmation, at once, and resumes without one', async () => {
    let items = flags();
    const bodies: unknown[] = [];
    server.use(
      http.get(mswPath('/api/v1/etl/flags'), () => HttpResponse.json({ items })),
      http.put(mswPath('/api/v1/etl/flags/{key}'), async ({ request, params }) => {
        const body = (await request.json()) as { value: boolean };
        bodies.push({ key: params.key, ...body });
        items = items.map((item) =>
          item.key === params.key ? { ...item, value: body.value, updatedBy: 'user:operator' } : item,
        );
        return HttpResponse.json(items.find((item) => item.key === params.key));
      }),
    );
    const user = userEvent.setup();
    await openControls('operator');

    await user.click(await gtfsSwitch());
    const dialog = await screen.findByRole('dialog', { name: copy.confirm.pauseTitle('GTFS-realtime') });
    await user.click(within(dialog).getByRole('button', { name: copy.confirm.pause }));
    expect(await gtfsSwitch()).toHaveAttribute('aria-checked', 'false');
    await waitFor(() => {
      expect(notify.success).toHaveBeenCalledWith(copy.feedback.paused);
    });
    expect(bodies).toEqual([{ key: 'etl.consumer.gtfs-rt.paused', value: true }]);

    await user.click(await gtfsSwitch());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(notify.success).toHaveBeenCalledWith(copy.feedback.resumed);
    });
    expect(bodies).toHaveLength(2);
  });

  it('puts the switch back and says why when the change fails', async () => {
    serveFlags(flags());
    server.use(respondProblem('put', '/api/v1/etl/flags/{key}', 422, 'invalid-flag-value'));
    const user = userEvent.setup();
    await openControls('operator');

    const feature = copy.feature['triage.dlq.enabled'].label;
    await user.click(await screen.findByRole('switch', { name: feature }));
    const dialog = await screen.findByRole('dialog', { name: copy.confirm.turnOffTitle(feature) });
    expect(within(dialog).getByText(copy.feature['triage.dlq.enabled'].offBody)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: copy.confirm.turnOff }));
    await waitFor(() => {
      expect(notify.error).toHaveBeenCalledWith(en.error.slug['invalid-flag-value'].title(), undefined);
    });
    expect(screen.getByRole('switch', { name: feature })).toHaveAttribute('aria-checked', 'true');
  });

  it('turns a feature on without asking', async () => {
    serveFlags(flags({ 'triage.auto-replay.enabled': { value: false } }));
    const user = userEvent.setup();
    await openControls('operator');
    const feature = copy.feature['triage.auto-replay.enabled'].label;
    await user.click(await screen.findByRole('switch', { name: feature }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(notify.success).toHaveBeenCalledWith(copy.feedback.turnedOn(feature));
    });
  });

  it('shows no guessed switch when E-55 fails', async () => {
    server.use(respondProblem('get', '/api/v1/etl/flags', 500, 'internal-error'));
    await openControls('operator');
    expect(await screen.findByText(en.error.couldntLoad(copy.sections.pipeline))).toBeInTheDocument();
    expect(screen.getByText(en.error.couldntLoad(copy.sections.ai))).toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    // The other blocks still work (DOC-37 §2.6).
    const feeds = screen.getByRole('region', { name: copy.sections.feeds });
    expect(await within(feeds).findByRole('table')).toBeInTheDocument();
  });
});

describe('Controls: Run a job', () => {
  it('runs OtpScorecardJob for yesterday and follows the request to its run (AC-5)', async () => {
    serveFlags(flags());
    const posts: { key: string | null; body: unknown }[] = [];
    let status = 'PENDING';
    server.use(
      http.post(mswPath('/api/v1/etl/jobs'), async ({ request }) => {
        posts.push({ key: request.headers.get('Idempotency-Key'), body: await request.json() });
        return HttpResponse.json(
          {
            id: 'req-1',
            kind: 'RUN',
            jobName: 'OtpScorecardJob',
            parameters: { serviceDates: '2026-09-28' },
            status: 'PENDING',
            requestedBy: 'user:operator',
            requestedAt: '2026-09-29T21:20:03Z',
          },
          { status: 202 },
        );
      }),
      http.get(mswPath('/api/v1/etl/job-requests/{id}'), () =>
        HttpResponse.json({
          id: 'req-1',
          kind: 'RUN',
          jobName: 'OtpScorecardJob',
          parameters: {},
          status,
          requestedBy: 'user:operator',
          requestedAt: '2026-09-29T21:20:03Z',
          ...(status === 'PENDING' ? {} : { runId: 'job:4130', jobExecutionId: 4130 }),
        }),
      ),
    );
    const user = userEvent.setup();
    await openControls('operator');

    const card = screen.getByRole('region', { name: copy.sections.runJob });
    expect(within(card).getByRole('combobox', { name: copy.job.label })).toHaveValue('OtpScorecardJob');
    await waitFor(() => {
      expect(within(card).getByText('Mon, Sep 28')).toBeInTheDocument();
    });
    await user.click(within(card).getByRole('button', { name: copy.job.run }));
    const dialog = await screen.findByRole('dialog', { name: copy.confirm.runTitle('OtpScorecardJob') });
    expect(within(dialog).getByText('2026-09-28')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: copy.job.run }));

    await waitFor(() => {
      expect(posts).toHaveLength(1);
    });
    expect(posts[0]?.body).toEqual({ jobName: 'OtpScorecardJob', parameters: { serviceDates: ['2026-09-28'] } });
    expect(posts[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(notify.success).toHaveBeenCalledWith(copy.feedback.requestSent);
    const recent = await screen.findByRole('region', { name: copy.job.recent });
    expect(await within(recent).findByText(en.status.jobRequest.PENDING)).toBeInTheDocument();

    status = 'RUNNING';
    expect(await within(recent).findByText(en.status.jobRequest.RUNNING, {}, { timeout: 4_000 })).toBeInTheDocument();
    expect(within(recent).getByRole('link', { name: copy.job.runNumber('4130') })).toHaveAttribute(
      'href',
      '/ops/jobs?run=job%3A4130',
    );
  });

  it('refuses today without sending anything (AC-6)', async () => {
    serveFlags(flags());
    const post = vi.fn();
    server.use(
      http.post(mswPath('/api/v1/etl/jobs'), () => {
        post();
        return HttpResponse.json({}, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    await openControls('operator');
    const card = screen.getByRole('region', { name: copy.sections.runJob });
    await waitFor(() => {
      expect(within(card).getByText('Mon, Sep 28')).toBeInTheDocument();
    });

    await user.type(within(card).getByLabelText(copy.job.field.addDate), '2026-09-29');
    await user.click(within(card).getByRole('button', { name: copy.job.field.addDate }));
    expect(within(card).getByText(copy.job.error.beforeToday)).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: copy.job.run })).toBeDisabled();
    expect(post).not.toHaveBeenCalled();
  });

  it('puts the API field errors under the field and job-not-allowed above the form', async () => {
    serveFlags(flags());
    server.use(
      http.post(mswPath('/api/v1/etl/jobs'), () =>
        HttpResponse.json(
          {
            type: 'urn:pti:problem:validation-error',
            title: 'Bad Request',
            status: 400,
            traceId: 't',
            errors: [{ field: 'parameters.serviceDates', message: 'every date must be before today (2026-09-28)' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    await openControls('operator');
    const card = screen.getByRole('region', { name: copy.sections.runJob });
    await waitFor(() => {
      expect(within(card).getByText('Mon, Sep 28')).toBeInTheDocument();
    });
    await user.click(within(card).getByRole('button', { name: copy.job.run }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: copy.job.run }));
    expect(await within(card).findByText('every date must be before today (2026-09-28)')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    server.use(
      http.post(mswPath('/api/v1/etl/jobs'), () =>
        HttpResponse.json(
          {
            type: 'urn:pti:problem:job-not-allowed',
            title: 'Unprocessable',
            status: 422,
            traceId: 't',
            detail: 'PartitionMaintenanceJob is disabled here.',
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    await user.selectOptions(within(card).getByRole('combobox'), 'PartitionMaintenanceJob');
    expect(within(card).getByText(copy.job.description.PartitionMaintenanceJob)).toBeInTheDocument();
    await user.click(within(card).getByRole('button', { name: copy.job.run }));
    expect(within(await screen.findByRole('dialog')).getByText(copy.confirm.noParameters)).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: copy.job.run }));
    expect(await within(card).findByText('PartitionMaintenanceJob is disabled here.')).toBeInTheDocument();
  });

  it('says the times are business time when the clock is shifted', async () => {
    serveFlags(flags());
    server.use(
      respond('get', '/api/v1/system/freshness', {
        businessNow: '2026-09-29T21:19:35Z',
        clockOffset: 'PT14H30M',
        checkedAt: '2026-09-29T21:19:30Z',
        stale: false,
        sources: [],
        insights: {},
        activeFeed: {
          feedVersionId: 3,
          publisherFeedVersion: '2026-08-23',
          timezone: 'America/Chicago',
          validFrom: '2026-08-23',
          validTo: '2026-12-12',
          activatedAt: '2026-09-27T08:34:40Z',
        },
      }),
    );
    const user = userEvent.setup();
    await openControls('operator');
    const card = screen.getByRole('region', { name: copy.sections.runJob });
    await user.selectOptions(within(card).getByRole('combobox'), 'AnalyticsRecomputeJob');
    expect(await within(card).findByText(copy.job.field.businessTime)).toBeInTheDocument();
  });
});

describe('Controls: schedule feed', () => {
  it('shows the active feed and its history', async () => {
    serveFlags(flags());
    await openControls('viewer');
    const card = screen.getByRole('region', { name: copy.sections.feeds });
    expect(await within(card).findByText('2026-08-23', { selector: 'span.font-semibold' })).toBeInTheDocument();
    expect(within(card).getByText(copy.feed.checks(0, 14))).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: copy.feed.byRun('3810') })).toHaveAttribute(
      'href',
      '/ops/jobs?run=job%3A3810',
    );
    expect(within(card).getByRole('table', { name: copy.feed.historyCaption(1) })).toBeInTheDocument();
  });

  it('says when no feed was loaded yet', async () => {
    serveFlags(flags());
    server.use(respond('get', '/api/v1/etl/feeds', { items: [] }));
    await openControls('viewer');
    expect(await screen.findByText(copy.feed.empty)).toBeInTheDocument();
  });

  it('checks the URL of the next feed and loads it with GtfsStaticLoadJob', async () => {
    serveFlags(flags());
    const bodies: unknown[] = [];
    server.use(
      http.post(mswPath('/api/v1/etl/jobs'), async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(
          {
            id: 'req-2',
            kind: 'RUN',
            jobName: 'GtfsStaticLoadJob',
            parameters: {},
            status: 'PENDING',
            requestedBy: 'user:operator',
            requestedAt: '2026-09-29T21:20:03Z',
          },
          { status: 202 },
        );
      }),
    );
    const user = userEvent.setup();
    await openControls('operator');
    const card = screen.getByRole('region', { name: copy.sections.feeds });
    const input = within(card).getByLabelText(copy.feed.urlLabel);
    await user.type(input, 'http://example.org/gtfs.zip');
    await user.click(within(card).getByRole('button', { name: copy.feed.load }));
    expect(within(card).getByText(copy.job.error.uri)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.clear(input);
    await user.type(input, 's3://raw/gtfs-static/next.zip');
    await user.click(within(card).getByRole('button', { name: copy.feed.load }));
    const dialog = await screen.findByRole('dialog', { name: copy.confirm.runTitle('GtfsStaticLoadJob') });
    await user.click(within(dialog).getByRole('button', { name: copy.job.run }));
    await waitFor(() => {
      expect(bodies).toEqual([
        { jobName: 'GtfsStaticLoadJob', parameters: { sourceUri: 's3://raw/gtfs-static/next.zip' } },
      ]);
    });
    const recent = await screen.findByRole('region', { name: copy.job.recent });
    expect(within(recent).getByText('GtfsStaticLoadJob')).toBeInTheDocument();
  });
});

describe('Controls: anchors', () => {
  it('lands on Run a job from the Pipeline button', async () => {
    serveFlags(flags());
    const scrolled: string[] = [];
    vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(function (this: Element) {
      scrolled.push(this.id);
    });
    const user = userEvent.setup();
    const { router } = await renderRoute('/ops/jobs', { as: 'operator' });
    await user.click(await screen.findByRole('link', { name: pipelineCopy.pipeline.runJob }));
    await screen.findByRole('heading', { level: 1, name: copy.title });
    expect(router.state.location.pathname).toBe('/ops/controls');
    expect(router.state.location.hash).toBe('run-job');
    // Once the blocks above have loaded, so that the page is tall enough to scroll.
    await waitFor(() => {
      expect(scrolled).toEqual(['run-job']);
    });
  });
});
