import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { demoCopy as copy } from '@/i18n/demo';
import { en } from '@/i18n/en';
import { mswPath, respond } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';
import { simHandlers, simRun, simStatus } from '@/test/sim';

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

function problem(status: number, slug: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `urn:pti:problem:${slug}`, title: slug, status, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** What the page sent to the proxy, by method and path. */
function recordSim() {
  const sent: { method: string; path: string; body: unknown }[] = [];
  server.events.on('request:start', ({ request }) => {
    const path = new URL(request.url).pathname;
    if (!path.startsWith('/api/v1/sim/') || request.method === 'GET') return;
    void request
      .clone()
      .text()
      .then((text) => sent.push({ method: request.method, path, body: text ? JSON.parse(text) : undefined }));
  });
  return sent;
}

async function openDemo(options: { env?: { demoControl: boolean } } = {}) {
  const result = await renderRoute('/ops/demo', { as: 'operator', env: { demoControl: true, ...options.env } });
  return result;
}

/** The Simulator band once the status has loaded (the skeleton is another element). */
async function simulatorStrip() {
  await screen.findByText(copy.simulator.vehicles);
  return screen.getByRole('region', { name: copy.simulator.label });
}

/** The library card of a scenario. */
async function scenarioCard(title: string) {
  const library = await screen.findByRole('region', { name: copy.library.heading });
  return within(library).findByRole('article', { name: title });
}

beforeEach(() => {
  server.use(...simHandlers());
});

describe('Demo control (DOC-36 screens/demo-control)', () => {
  it('shows the simulator: business time, vehicles and throughput (AC-1)', async () => {
    await openDemo();
    expect(await screen.findByRole('heading', { level: 1, name: copy.heading })).toBeInTheDocument();
    const strip = await simulatorStrip();
    expect(within(strip).getByText('Oct 2, 4:40 PM')).toBeInTheDocument();
    expect(within(strip).getByText('+14 h 30 min')).toBeInTheDocument();
    expect(within(strip).getByText('598')).toBeInTheDocument();
    expect(within(strip).getByText(/^141\.4/)).toBeInTheDocument();
    expect(within(strip).getByRole('radio', { name: '1×' })).toBeChecked();
    expect(screen.getByText(copy.running.none)).toBeInTheDocument();
    expect(await screen.findByText(copy.history.empty)).toBeInTheDocument();
    // The catalog in its order, each with "Start scenario".
    const cards = within(await screen.findByRole('region', { name: copy.library.heading })).getAllByRole('article');
    expect(cards.map((card) => within(card).getByRole('heading').textContent)).toEqual([
      'Bus bunching',
      'Service disruption',
      'Bad data',
      'Ticket sales spike',
      'Load ramp',
    ]);
  });

  it('says the demo profile is off when env.js says so, without asking the proxy (AC-6)', async () => {
    const asked = vi.fn();
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname.startsWith('/api/v1/sim/')) asked();
    });
    await openDemo({ env: { demoControl: false } });
    expect(await screen.findByRole('heading', { name: copy.notEnabled })).toBeInTheDocument();
    expect(screen.getByText(copy.notEnabledBody)).toBeInTheDocument();
    expect(asked).not.toHaveBeenCalled();
  });

  it('says the demo profile is off when the proxy answers 404 (AC-6)', async () => {
    server.use(http.get(mswPath('/api/v1/sim/status'), () => problem(404, 'not-found')));
    await openDemo();
    expect(await screen.findByRole('heading', { name: copy.notEnabled })).toBeInTheDocument();
  });

  it('keeps the page when the simulator does not answer, with "Start scenario" off (§7)', async () => {
    server.use(http.get(mswPath('/api/v1/sim/status'), () => problem(502, 'simulator-unavailable')));
    await openDemo();
    const strip = await screen.findByRole('region', { name: copy.simulator.label });
    expect(await within(strip).findByText("Simulator isn't responding")).toBeInTheDocument();
    expect(within(strip).getByRole('button', { name: en.common.retry })).toBeInTheDocument();
    const card = await scenarioCard('Bad data');
    expect(within(card).getByRole('button', { name: copy.library.start })).toBeDisabled();
  });

  it('refuses pairs = 5 in the browser and sends nothing (AC-5)', async () => {
    const user = userEvent.setup();
    const sent = recordSim();
    await openDemo();
    await user.click(within(await scenarioCard('Bus bunching')).getByRole('button', { name: copy.library.start }));
    const dialog = await screen.findByRole('dialog', { name: copy.dialog.title('Bus bunching') });
    expect(within(dialog).getByText(/Stops by itself after 20 min\./)).toBeInTheDocument();
    // No route yet: the button is off and no message shows.
    expect(within(dialog).getByRole('button', { name: copy.dialog.start })).toBeDisabled();

    await user.click(within(dialog).getByRole('button', { name: 'Route' }));
    await user.click(await screen.findByRole('checkbox', { name: /Nicollet/ }));
    expect(within(dialog).getByRole('button', { name: copy.dialog.start })).toBeEnabled();

    const pairs = within(dialog).getByLabelText('Vehicle pairs');
    await user.clear(pairs);
    await user.type(pairs, '5');
    expect(within(dialog).getByText('Must be at most 3.')).toBeInTheDocument();
    expect(pairs).toHaveAttribute('aria-invalid', 'true');
    expect(within(dialog).getByRole('button', { name: copy.dialog.start })).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: copy.dialog.reset }));
    expect(within(dialog).getByLabelText('Vehicle pairs')).toHaveValue(1);
    expect(sent).toEqual([]);
  });

  it('says a second run on the same target is already running, and keeps the dialog (AC-3)', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(mswPath('/api/v1/sim/scenarios/bunching'), () =>
        problem(409, 'scenario-conflict', { detail: 'bunching already runs for route 18 direction 0.' }),
      ),
    );
    await openDemo();
    await user.click(within(await scenarioCard('Bus bunching')).getByRole('button', { name: copy.library.start }));
    const dialog = await screen.findByRole('dialog', { name: copy.dialog.title('Bus bunching') });
    await user.click(within(dialog).getByRole('button', { name: 'Route' }));
    await user.click(await screen.findByRole('checkbox', { name: /Nicollet/ }));
    await user.click(within(dialog).getByRole('button', { name: copy.dialog.start }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(copy.errors.conflict('Bus bunching'));
    expect(screen.getByRole('dialog', { name: copy.dialog.title('Bus bunching') })).toBeInTheDocument();
  });

  it('shows a field error the simulator returns under its field', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(mswPath('/api/v1/sim/scenarios/bad-data'), () =>
        problem(400, 'invalid-param', { errors: [{ field: 'ratio', message: 'must be at most 0.5' }] }),
      ),
    );
    await openDemo();
    await user.click(within(await scenarioCard('Bad data')).getByRole('button', { name: copy.library.start }));
    const dialog = await screen.findByRole('dialog', { name: copy.dialog.title('Bad data') });
    await user.click(within(dialog).getByRole('button', { name: copy.dialog.start }));
    expect(await within(dialog).findByText('must be at most 0.5')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Share of messages')).toHaveAttribute('aria-invalid', 'true');
  });

  it('starts a scenario with the catalog body and offers where to watch it (§6)', async () => {
    const user = userEvent.setup();
    const sent = recordSim();
    await openDemo();
    await user.click(
      within(await scenarioCard('Service disruption')).getByRole('button', { name: copy.library.start }),
    );
    const dialog = await screen.findByRole('dialog', { name: copy.dialog.title('Service disruption') });
    await user.click(within(dialog).getByRole('button', { name: 'Route' }));
    await user.click(await screen.findByRole('checkbox', { name: /Nicollet/ }));
    await user.selectOptions(within(dialog).getByLabelText('Direction'), '1');
    const duration = within(dialog).getByLabelText('Duration');
    await user.clear(duration);
    await user.type(duration, '5');
    await user.click(within(dialog).getByRole('button', { name: copy.dialog.start }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: copy.dialog.title('Service disruption') })).not.toBeInTheDocument();
    });
    expect(sent).toEqual([
      {
        method: 'POST',
        path: '/api/v1/sim/scenarios/disruption',
        body: { routeId: '18', directionId: 1, skipStops: false, duration: 'PT5M' },
      },
    ]);
    const [text, options] = notify.success.mock.calls.at(-1) as [string, { action?: { label: string } }];
    expect(text).toBe(copy.feedback.started('Service disruption'));
    expect(options.action?.label).toBe(copy.feedback.follow.map);
  });

  it('shows a running run with its time left and reactions, and stops it without asking (§4.1, §6)', async () => {
    const user = userEvent.setup();
    const running = simRun();
    const sent = recordSim();
    server.use(
      ...simHandlers({
        status: { ...simStatus, runningScenarios: [{ runId: running.runId, scenario: 'bunching' }] },
        runs: [running],
      }),
      respond('get', '/api/v1/alerts', {
        items: [
          {
            id: '0192f6a0-0000-7000-8000-000000000001',
            type: 'BUNCHING',
            severity: 1,
            audience: 'OPERATIONS',
            routeId: '18',
            title: 'Bus bunching on route 18',
            body: {},
            link: '/map?bunching=b1',
            refId: '0192f6a0-0000-7000-8000-0000000000b1',
            createdAt: new Date(Date.parse(running.startedAt) + 38_000).toISOString(),
          },
        ],
      }),
    );
    await openDemo();

    const runningRegion = await screen.findByRole('region', { name: copy.running.label });
    const card = await within(runningRegion).findByRole('article', { name: 'Bus bunching' });
    expect(within(card).getByText(/^Running · started .* by operator$/)).toBeInTheDocument();
    expect(within(card).getByText('Bus bunching · Nicollet Av - Nicollet Mall - 1st Av')).toBeInTheDocument();
    expect(
      within(card).getByText('Direction 0 · Vehicle pairs 1 · Target gap (share of headway) 0.2 · Duration 20 min'),
    ).toBeInTheDocument();
    expect(within(card).getByRole('img', { name: /% of the planned duration$/ })).toBeInTheDocument();
    expect(
      await within(card).findByText(`Bus bunching on route 18 · ${copy.running.afterStart('38 s')}`),
    ).toBeInTheDocument();
    expect(await within(card).findByText(/^Suggested action ready \(\d+%\)$/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: copy.feedback.follow.map })).toHaveAttribute('href', '/map?route=18');
    // The library card stays startable: bunching has no default route.
    expect(within(await scenarioCard('Bus bunching')).getByRole('button', { name: copy.library.start })).toBeEnabled();

    await user.click(within(card).getByRole('button', { name: copy.running.stop }));
    await waitFor(() => {
      expect(sent).toContainEqual({
        method: 'DELETE',
        path: `/api/v1/sim/scenario-runs/${running.runId}`,
        body: undefined,
      });
    });
    expect(notify.success).toHaveBeenCalledWith(copy.feedback.stopped('Bus bunching'));
  });

  it('asks before pausing both streams, then sets the rate (§6)', async () => {
    const user = userEvent.setup();
    const sent = recordSim();
    await openDemo();
    const strip = await simulatorStrip();
    await user.click(within(strip).getByRole('radio', { name: copy.speed.paused }));
    const confirm = await screen.findByRole('dialog', { name: copy.speed.pauseTitle('GTFS-rt and ticketing') });
    expect(sent).toEqual([]);
    await user.click(within(confirm).getByRole('button', { name: copy.speed.pauseConfirm }));
    await waitFor(() => {
      expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/sim/rate', body: { gtfsRt: 0, ticketing: 0 } }]);
    });
    expect(notify.success).toHaveBeenCalledWith(copy.speed.updated);
    expect(await within(strip).findByRole('radio', { name: copy.speed.paused })).toBeChecked();
  });

  it('sets the two multipliers apart from "Custom rate" and checks the range', async () => {
    const user = userEvent.setup();
    const sent = recordSim();
    await openDemo();
    const strip = await simulatorStrip();
    await user.click(within(strip).getByRole('button', { name: copy.speed.more }));
    const gtfsRt = await screen.findByLabelText(copy.speed.gtfsRt);
    await user.clear(gtfsRt);
    await user.type(gtfsRt, '25');
    expect(screen.getByText(copy.speed.range)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy.speed.apply })).toBeDisabled();
    await user.clear(gtfsRt);
    await user.type(gtfsRt, '2');
    await user.click(screen.getByRole('button', { name: copy.speed.apply }));
    await waitFor(() => {
      expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/sim/rate', body: { gtfsRt: 2, ticketing: 1 } }]);
    });
    expect(await within(strip).findByRole('radio', { name: copy.speed.custom })).toBeChecked();
  });

  it('says so when the rate is refused because a load ramp owns it (AC-4)', async () => {
    const user = userEvent.setup();
    server.use(http.put(mswPath('/api/v1/sim/rate'), () => problem(409, 'load-ramp-running')));
    await openDemo();
    const strip = await simulatorStrip();
    await user.click(within(strip).getByRole('radio', { name: '2×' }));
    await waitFor(() => {
      expect(notify.error).toHaveBeenCalledWith(copy.speed.loadRampRunning);
    });
  });

  it('disables Speed while a load ramp runs, and marks the scenario Running (AC-4)', async () => {
    const ramp = simRun({ scenario: 'load-ramp', params: { steps: [1, 2], stepDuration: 'PT1M', rampDown: true } });
    server.use(
      ...simHandlers({
        status: { ...simStatus, runningScenarios: [{ runId: ramp.runId, scenario: 'load-ramp' }] },
        runs: [ramp],
      }),
    );
    await openDemo();
    const strip = await simulatorStrip();
    expect(within(strip).getByRole('radio', { name: '2×' })).toBeDisabled();
    expect(within(strip).getByRole('button', { name: copy.speed.more })).toBeDisabled();
    // A single scenario that runs is "Running" in the library, without "Start scenario".
    const card = await scenarioCard('Load ramp');
    expect(await within(card).findByText(copy.library.running)).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: copy.library.start })).not.toBeInTheDocument();
  });

  it('opens a run of the history in the drawer and runs it again with its parameters (§6)', async () => {
    const user = userEvent.setup();
    const done = simRun({
      runId: '0192f7b1-0000-7000-8000-00000000d0e1',
      scenario: 'bad-data',
      status: 'STOPPED',
      requestedBy: 'experiment:EXP-03/20261002T101500Z-r07',
      params: { ratio: 0.2, kinds: ['unknown_route'], duration: 'PT2M' },
      endedAt: new Date().toISOString(),
    });
    server.use(...simHandlers({ runs: [done] }));
    const { router } = await openDemo();

    const table = await screen.findByRole('table', { name: copy.history.caption(1) });
    const [, row] = within(table).getAllByRole('row');
    if (!row) throw new Error('no history row');
    expect(row).toHaveTextContent('Bad data');
    expect(row).toHaveTextContent('Stopped');
    expect(row).toHaveTextContent('Experiment EXP-03');
    expect(row).toHaveTextContent('Share of messages 0.2 · Kinds of corruption unknown_route · Duration 2…');
    await user.click(row);
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ run: done.runId });
    });
    const drawer = await screen.findByRole('dialog', { name: /Scenario run 0192f7b1/ });
    expect(await within(drawer).findByText(copy.drawer.requestedBy('Experiment EXP-03'))).toBeInTheDocument();

    await user.click(within(drawer).getByRole('button', { name: copy.drawer.runAgain }));
    const dialog = await screen.findByRole('dialog', { name: copy.dialog.title('Bad data') });
    expect(within(dialog).getByLabelText('Share of messages')).toHaveValue(0.2);
    expect(within(dialog).getByRole('checkbox', { name: 'unknown_route' })).toBeChecked();
    expect(within(dialog).getByRole('checkbox', { name: 'malformed_json' })).not.toBeChecked();
  });

  it('says a run that no longer exists is gone', async () => {
    await renderRoute('/ops/demo?run=missing', { as: 'operator', env: { demoControl: true } });
    expect(await screen.findByText(copy.drawer.gone)).toBeInTheDocument();
  });
});
