import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { getFreshness, listAlerts, listRoutes, listRuntimeFlags } from '@/api/generated/examples';
import { en } from '@/i18n/en';
import { overviewCopy } from '@/i18n/overview';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const fresh = getFreshness.examples.fresh;
const [exampleAlert] = listAlerts.examples.disruption.items;
if (!exampleAlert) throw new Error('example has no alert');
const unacknowledged = { ...exampleAlert, acknowledgedAt: undefined, acknowledgedBy: undefined };

let requested: string[] = [];
beforeEach(() => {
  requested = [];
  server.events.on('request:start', ({ request }) => {
    requested.push(new URL(request.url).pathname);
  });
});
afterEach(() => {
  server.events.removeAllListeners();
});

function primaryNav() {
  return within(screen.getByRole('navigation', { name: en.nav.primary }));
}

function navLabels() {
  return primaryNav()
    .getAllByRole('link')
    .map((link) => link.textContent);
}

describe('navigation by role (DOC-34 §4.1)', () => {
  it('AC-1 shows anonymous users the Network group and "Sign in" only', async () => {
    await renderRoute('/map');
    await screen.findByRole('heading', { level: 1, name: en.nav.items.map });

    expect(navLabels()).toEqual([en.nav.items.map, en.nav.items.stops, en.nav.items.alerts]);
    expect(primaryNav().queryByRole('heading', { name: en.nav.groups.operations })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.account.signIn })).toBeInTheDocument();
    // Anonymous never calls the ops endpoints behind the counts (the map itself asks for public disruptions).
    expect(requested.filter((path) => path.includes('/etl/') || path.includes('/insights/ticketing'))).toEqual([]);
    expect(requested).not.toContain('/api/v1/me');
  });

  it('AC-2 sends a viewer from / to the overview, with Scorecard, Operations "Read-only" and no Demo', async () => {
    const { router } = await renderRoute('/', { as: 'viewer', env: { demoControl: true } });
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/overview');
    });

    await primaryNav().findByRole('link', { name: en.nav.items.overview });
    for (const item of [en.nav.items.scorecard, en.nav.items.pipeline, en.nav.items.controls]) {
      expect(primaryNav().getByRole('link', { name: new RegExp(`^${item}`) })).toBeInTheDocument();
    }
    expect(primaryNav().queryByRole('link', { name: en.nav.items.demo })).not.toBeInTheDocument();
    expect(primaryNav().getByText(en.nav.readOnly)).toBeInTheDocument();
  });

  it('AC-3 shows operators Demo when demoControl is on, and marks Pipeline current on /ops/jobs', async () => {
    await renderRoute('/ops/jobs', { as: 'operator', env: { demoControl: true } });
    const pipeline = await primaryNav().findByRole('link', { name: en.nav.items.pipeline });
    expect(pipeline).toHaveAttribute('aria-current', 'page');
    expect(primaryNav().getByRole('link', { name: en.nav.items.demo })).toBeInTheDocument();
    expect(primaryNav().queryByText(en.nav.readOnly)).not.toBeInTheDocument();
  });

  it('hides Demo from operators when env.js leaves demoControl off', async () => {
    await renderRoute('/ops/jobs', { as: 'operator', env: { demoControl: false } });
    await primaryNav().findByRole('link', { name: en.nav.items.pipeline });
    expect(primaryNav().queryByRole('link', { name: en.nav.items.demo })).not.toBeInTheDocument();
  });

  it('shows the counts of the menu for a viewer', async () => {
    server.use(respond('get', '/api/v1/alerts', { items: [unacknowledged] }));
    await renderRoute('/overview', { as: 'viewer' });

    expect(await screen.findByText(en.nav.unacknowledgedAlerts(1))).toBeInTheDocument();
    expect(await screen.findByText(en.nav.openDeadLetters(214))).toBeInTheDocument();
    expect(await screen.findByText(en.nav.runningReplays(1))).toBeInTheDocument();
    expect(await screen.findByText(en.nav.ticketingAnomalies(1))).toBeInTheDocument();
    expect(await screen.findByText(en.nav.pipelineFailed)).toBeInTheDocument();
  });

  it('AC-7 marks Controls "Paused" while a pause flag is on', async () => {
    const [flag] = listRuntimeFlags.examples.flags.items;
    if (!flag) throw new Error('example has no flag');
    server.use(respond('get', '/api/v1/etl/flags', { items: [{ ...flag, value: true }] }));
    await renderRoute('/map', { as: 'viewer' });
    const controls = await primaryNav().findByRole('link', { name: new RegExp(en.nav.items.controls) });
    await waitFor(() => {
      expect(within(controls).getByText(en.nav.paused)).toBeInTheDocument();
    });
  });

  it('caps a count at 99+', async () => {
    server.use(
      respond('get', '/api/v1/etl/dlq/summary', {
        open: 1234,
        createdLastHour: 0,
        byStatus: {},
        openBySeverity: {},
        openBySource: {},
      }),
    );
    await renderRoute('/overview', { as: 'viewer' });
    const deadLetters = await primaryNav().findByRole('link', { name: new RegExp(en.nav.items.deadLetters) });
    await waitFor(() => {
      expect(within(deadLetters).getByText('99+')).toBeInTheDocument();
    });
  });
});

describe('the start page (UX-11)', () => {
  it.each([
    ['anonymous', '/map'],
    ['viewer', '/overview'],
    ['operator', '/overview'],
  ] as const)('sends %s to %s', async (as, target) => {
    const { router } = await renderRoute('/', { as });
    await waitFor(() => {
      expect(router.state.location.pathname).toBe(target);
    });
  });

  it('redirects /ops to Pipeline', async () => {
    const { router } = await renderRoute('/ops', { as: 'viewer' });
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/ops/jobs');
    });
  });
});

describe('pages that need a role (DOC-37 §2.5)', () => {
  it('UX-03 asks anonymous users to sign in on /ops/jobs and requests nothing for the page', async () => {
    const { session } = await renderRoute('/ops/jobs');
    expect(await screen.findByRole('heading', { name: en.states.noAccess.signInTitle })).toBeInTheDocument();
    expect(requested.filter((path) => path.startsWith('/api/v1/etl/'))).toEqual([]);

    const main = within(screen.getByRole('main'));
    await userEvent.click(main.getByRole('button', { name: en.common.signIn }));
    expect(session.signIn).toHaveBeenCalledTimes(1);
  });

  it('tells a viewer that Demo needs the operator role', async () => {
    await renderRoute('/ops/demo', { as: 'viewer', env: { demoControl: true } });
    expect(await screen.findByRole('heading', { name: en.states.noAccess.forbiddenTitle })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.states.noAccess.goToOverview })).toBeInTheDocument();
  });

  it('answers 404 for Demo when demoControl is off', async () => {
    await renderRoute('/ops/demo', { as: 'operator', env: { demoControl: false } });
    expect(await screen.findByRole('heading', { level: 1, name: en.notFound.title })).toBeInTheDocument();
  });

  it('says sign-in is not available on a deployment without Keycloak', async () => {
    await renderRoute('/ops/dlq', { session: { available: false } });
    expect(await screen.findByRole('heading', { name: en.states.noAccess.unavailableTitle })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.account.signIn })).not.toBeInTheDocument();
  });

  it('shows a skeleton, not "Sign in", while the session is restored', async () => {
    await renderRoute('/map', { as: 'restoring' });
    await screen.findByRole('heading', { level: 1, name: en.nav.items.map });
    expect(screen.getByTestId('account-skeleton')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.account.signIn })).not.toBeInTheDocument();
  });
});

describe('account menu', () => {
  it('shows the name and role, and signs out', async () => {
    const { session } = await renderRoute('/overview', { as: 'operator' });
    expect(await screen.findByText(en.account.roleLine.operator)).toBeInTheDocument();
    expect(screen.getByText('Linh Tran')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: en.account.menu }));
    expect(await screen.findByText(en.account.signedInAs('Linh Tran'))).toBeInTheDocument();
    expect(screen.getByText(en.account.role(en.account.roles.operator))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('menuitem', { name: en.account.signOut }));
    expect(session.signOut).toHaveBeenCalledTimes(1);
  });

  it('opens the keyboard shortcuts', async () => {
    await renderRoute('/overview', { as: 'viewer' });
    await userEvent.click(await screen.findByRole('button', { name: en.account.menu }));
    await userEvent.click(await screen.findByRole('menuitem', { name: en.account.shortcuts }));
    const dialog = await screen.findByRole('dialog', { name: en.shortcuts.title });
    expect(within(dialog).getByText(en.shortcuts.rows[1].label)).toBeInTheDocument();
  });
});

describe('StaleBanner (DOC-37 §2.4)', () => {
  it('AC-6 shows the delay of the live data while E-60 says stale', async () => {
    server.use(
      respond('get', '/api/v1/system/freshness', {
        ...fresh,
        stale: true,
        sources: [
          {
            source: 'GTFS_RT_VEHICLE_POSITION',
            lastEventAt: '2026-09-29T21:12:00Z',
            ageSeconds: 420,
            staleAfterSeconds: 120,
            stale: true,
          },
        ],
      }),
    );
    await renderRoute('/map');
    const banner = await screen.findByTestId('stale-banner');
    await waitFor(() => {
      expect(banner).toHaveTextContent(en.banner.stale(en.source.GTFS_RT_VEHICLE_POSITION, '7 min'));
    });
  });

  it('says freshness is unknown when E-60 answers 503', async () => {
    server.use(respondProblem('get', '/api/v1/system/freshness', 503, 'service-unavailable'));
    await renderRoute('/map');
    await waitFor(() => {
      expect(screen.getByTestId('stale-banner')).toHaveTextContent(en.banner.freshnessUnknown);
    });
  });

  it('is absent on pages without live data, such as Controls', async () => {
    server.use(respond('get', '/api/v1/system/freshness', { ...fresh, stale: true, sources: [] }));
    await renderRoute('/ops/controls', { as: 'viewer' });
    await screen.findByRole('heading', { level: 1, name: en.nav.items.controls });
    expect(screen.queryByTestId('stale-banner')).not.toBeInTheDocument();
  });
});

describe('footer', () => {
  it('shows the simulated clock when the business clock is shifted', async () => {
    server.use(respond('get', '/api/v1/system/freshness', { ...fresh, clockOffset: '-PT13H' }));
    await renderRoute('/alerts');
    expect(await screen.findByText(/^Simulated clock: Sep 29, 4:19 PM CDT$/)).toBeInTheDocument();
  });

  it('leaves it out at PT0S', async () => {
    await renderRoute('/alerts');
    expect(await screen.findByText(en.footer.attribution)).toBeInTheDocument();
    expect(screen.queryByText(/Simulated clock/)).not.toBeInTheDocument();
  });
});

describe('search (⌘K)', () => {
  it('AC-9 finds route 18 and opens the map filtered to it', async () => {
    const { router } = await renderRoute('/overview', { as: 'viewer' });
    await screen.findByRole('heading', { level: 1, name: overviewCopy.overview.title });
    await userEvent.keyboard('{Meta>}k{/Meta}');
    const input = await screen.findByPlaceholderText(en.search.placeholder);
    await userEvent.type(input, '18');

    const route = listRoutes.examples.routes.items.find((item) => item.routeId === '18');
    if (!route) throw new Error('example has no route 18');
    const option = await screen.findByRole('option', { name: new RegExp(route.longName) });
    expect(option.closest('[cmdk-group]')).toHaveTextContent(en.search.groups.routes);
    await userEvent.keyboard('{Enter}');
    await waitFor(() => {
      expect(router.state.location.href).toBe('/map?route=18');
    });
  });

  it('lists only the pages the user may open', async () => {
    await renderRoute('/map');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${en.search.label}`) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('option', { name: en.nav.items.map })).toBeInTheDocument();
    expect(within(dialog).queryByRole('option', { name: en.nav.items.overview })).not.toBeInTheDocument();
  });

  it('searches stops from two characters and opens the stop', async () => {
    const { router } = await renderRoute('/map');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${en.search.label}`) }));
    await userEvent.type(await screen.findByPlaceholderText(en.search.placeholder), 'Nic');
    await userEvent.click(await screen.findByRole('option', { name: /Nicollet Ave & 46th St/ }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/stops/51405');
    });
  });

  it('says so when stops cannot be searched', async () => {
    server.use(http.get(mswPath('/api/v1/stops'), () => HttpResponse.error()));
    await renderRoute('/map');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${en.search.label}`) }));
    await userEvent.type(await screen.findByPlaceholderText(en.search.placeholder), 'zzz');
    expect(await screen.findByText(en.search.stopsFailed)).toBeInTheDocument();
  });

  it('says when nothing matches', async () => {
    server.use(respond('get', '/api/v1/stops', { items: [] }));
    await renderRoute('/map');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${en.search.label}`) }));
    await userEvent.type(await screen.findByPlaceholderText(en.search.placeholder), 'qqqq');
    expect(await screen.findByText(en.search.noMatches('qqqq'))).toBeInTheDocument();
  });
});

describe('focus', () => {
  it('moves to the new page heading after a menu click', async () => {
    await renderRoute('/map');
    await screen.findByRole('heading', { level: 1, name: en.nav.items.map });
    await userEvent.click(primaryNav().getByRole('link', { name: en.nav.items.alerts }));
    const heading = await screen.findByRole('heading', { level: 1, name: en.nav.items.alerts });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
  });

  it('starts with a skip link to the content', async () => {
    await renderRoute('/map');
    await act(() => userEvent.tab());
    expect(screen.getByRole('link', { name: en.skipToContent })).toHaveFocus();
  });
});
