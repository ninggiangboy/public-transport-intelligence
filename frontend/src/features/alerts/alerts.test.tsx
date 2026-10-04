import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getBunchingEpisode, getDisruptionEpisode, getTicketingAnomaly, listAlerts } from '@/api/generated/examples';
import type { ResponseBody } from '@/api/types';
import { en } from '@/i18n/en';
import { upsertAlert } from '@/realtime/handlers';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

type Alert = ResponseBody<'/api/v1/alerts', 'get', 200>['items'][number];

const [example] = listAlerts.examples.disruption.items;
if (!example) throw new Error('example has no alert');

const disruption: Alert = { ...example, acknowledgedAt: undefined, acknowledgedBy: undefined };
const bunching: Alert = {
  id: 'b-1',
  type: 'BUNCHING',
  severity: 1,
  audience: 'OPERATIONS',
  routeId: '18',
  refId: getBunchingEpisode.examples['closed episode'].id,
  refTable: 'insight.insight_bus_bunching',
  title: 'Bus bunching on route 18 northbound: vehicles 1187 and 1203',
  body: { gapSeconds: 40, directionId: 0 },
  createdAt: '2026-09-29T21:12:20Z',
  link: '/map?route=18&bunching=6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c',
};
const ticketing: Alert = {
  id: 't-1',
  type: 'TICKETING_ANOMALY',
  severity: 0,
  audience: 'OPERATIONS',
  refId: getTicketingAnomaly.examples['pending enrichment'].id,
  title: 'Unusual ticket sales at Nicollet Mall Station kiosk 2',
  body: { salePointId: 'SP-0142', refundRatio: 0.48 },
  createdAt: '2026-09-29T21:15:30Z',
  link: '/ops/ticketing?anomaly=c1d2e3f4',
};
const infra: Alert = {
  id: 'i-1',
  type: 'INFRA',
  severity: 2,
  audience: 'ENGINEERING',
  title: 'Kafka broker down',
  body: {
    annotations: { summary: 'Broker 1 is unreachable', runbook_url: 'https://runbooks.example/kafka' },
    labels: { alertname: 'KafkaBrokerDown', severity: 'critical' },
    startsAt: '2026-09-29T21:00:00Z',
    generatorURL: 'http://grafana.example/explore',
  },
  createdAt: '2026-09-29T21:00:05Z',
  link: 'https://runbooks.example/kafka',
};

let alertRequests: URL[] = [];

function serveAlerts(items: Alert[]) {
  server.use(
    http.get(mswPath('/api/v1/alerts'), ({ request }) => {
      const url = new URL(request.url);
      alertRequests.push(url);
      const state = url.searchParams.get('state');
      const shown = state === 'unacknowledged' ? items.filter((alert) => !alert.acknowledgedAt) : items;
      return HttpResponse.json({ items: shown });
    }),
  );
}

beforeEach(() => {
  alertRequests = [];
  notify.error.mockClear();
  notify.success.mockClear();
});

function listRegion() {
  return within(screen.getByRole('region', { name: en.alerts.list }));
}

describe('alert feed', () => {
  it('AC-1 shows anonymous users public alerts without audience filter, unacknowledged tab or ack', async () => {
    serveAlerts([disruption]);
    await renderRoute('/alerts');
    expect(await screen.findByRole('heading', { level: 1, name: en.alerts.title })).toBeInTheDocument();
    expect(await listRegion().findByText(disruption.title)).toBeInTheDocument();

    expect(screen.queryByRole('button', { name: en.alerts.filters.audience })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /Unacknowledged/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.alerts.actions.acknowledge })).not.toBeInTheDocument();
    expect(alertRequests.every((url) => !url.searchParams.has('audience'))).toBe(true);
  });

  it('opens the first alert on wide screens, with the disruption detail of E-13', async () => {
    serveAlerts([disruption]);
    const { router } = await renderRoute('/alerts', { as: 'viewer' });
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ alert: disruption.id });
    });
    const detail = await screen.findByRole('article', { name: disruption.title });
    // E-13 example: 212.7 s now, 230.1 s peak, three stops, and the AI block for staff.
    expect(await within(detail).findByText('+3 min 33 s', { selector: 'dd' })).toBeInTheDocument();
    expect(within(detail).getByText('+3 min 50 s')).toBeInTheDocument();
    expect(within(detail).getByText(en.alerts.detailLabels.causeNotClassified)).toBeInTheDocument();
    expect(within(detail).getByRole('link', { name: en.alerts.actions.showOnMap })).toHaveAttribute(
      'href',
      disruption.link,
    );
    expect(within(detail).getByText(en.alerts.activity.detected)).toBeInTheDocument();
  });

  it('AC-3 acknowledges at once, without waiting for the server or a toast', async () => {
    serveAlerts([disruption]);
    server.use(
      http.post(mswPath('/api/v1/alerts/{id}/ack'), async () => {
        await delay(200);
        return HttpResponse.json({
          ...disruption,
          acknowledgedAt: '2026-09-29T21:20:00Z',
          acknowledgedBy: 'user:operator',
        });
      }),
    );
    await renderRoute(`/alerts?alert=${disruption.id}`, { as: 'operator' });
    const button = await screen.findByRole('button', { name: en.alerts.actions.acknowledge });
    expect(listRegion().getByRole('img', { name: en.alerts.unread })).toBeInTheDocument();

    await userEvent.click(button);
    expect(screen.getByText(en.alerts.acknowledgedByYou)).toBeInTheDocument();
    expect(listRegion().queryByRole('img', { name: en.alerts.unread })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText(en.alerts.acknowledgedByYou)).toBeInTheDocument();
    });
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('rolls the acknowledgement back and says so when it fails', async () => {
    serveAlerts([disruption]);
    server.use(respondProblem('post', '/api/v1/alerts/{id}/ack', 503, 'service-unavailable'));
    await renderRoute(`/alerts?alert=${disruption.id}`, { as: 'operator' });
    await userEvent.click(await screen.findByRole('button', { name: en.alerts.actions.acknowledge }));
    await waitFor(() => {
      expect(notify.error).toHaveBeenCalledWith(en.alerts.ackFailed);
    });
    expect(screen.getByRole('button', { name: en.alerts.actions.acknowledge })).toBeInTheDocument();
  });

  it('hides "Acknowledge" from viewers and shows who acknowledged', async () => {
    serveAlerts([{ ...disruption, acknowledgedAt: '2026-09-29T21:02:10Z', acknowledgedBy: 'user:dana' }]);
    await renderRoute(`/alerts?alert=${disruption.id}`, { as: 'viewer' });
    expect(await screen.findByText(en.alerts.acknowledgedBy('dana'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.alerts.actions.acknowledge })).not.toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByText(en.nav.readOnly)).toBeInTheDocument();
  });

  it('AC-2 puts an alert from the event stream at the top and announces it', async () => {
    serveAlerts([disruption]);
    const { queryClient } = await renderRoute('/alerts', { as: 'viewer' });
    await listRegion().findByText(disruption.title);
    act(() => {
      upsertAlert(queryClient, bunching, 'created');
    });
    const items = await waitFor(() => {
      const found = listRegion().getAllByRole('listitem');
      expect(found).toHaveLength(2);
      return found;
    });
    expect(items[0]).toHaveTextContent(bunching.title);
    await waitFor(() => {
      expect(screen.getByText(en.alerts.announce.one(bunching.title))).toBeInTheDocument();
    });
  });

  it('passes the filters of the URL to E-20 and offers to clear them when nothing matches', async () => {
    serveAlerts([]);
    const { router } = await renderRoute('/alerts?type=BUNCHING&severity=2&window=7d', { as: 'viewer' });
    expect(await screen.findByText(en.alerts.empty.filteredTitle)).toBeInTheDocument();
    const url = alertRequests.find((request) => request.searchParams.get('state') === 'open');
    expect(url?.searchParams.getAll('type')).toEqual(['BUNCHING']);
    expect(url?.searchParams.getAll('severity')).toEqual(['2']);

    await userEvent.click(screen.getByRole('button', { name: en.common.clearFilters }));
    await waitFor(() => {
      expect(router.state.location.href).toBe('/alerts?window=7d');
    });
  });

  it('says there are no open alerts when the feed is empty', async () => {
    serveAlerts([]);
    await renderRoute('/alerts');
    expect(await screen.findByText(en.alerts.empty.openTitle)).toBeInTheDocument();
  });

  it('switches the state with the tabs', async () => {
    serveAlerts([disruption]);
    const { router } = await renderRoute('/alerts', { as: 'operator' });
    await userEvent.click(await screen.findByRole('tab', { name: /Unacknowledged/ }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ state: 'unacknowledged' });
    });
    expect(alertRequests.some((url) => url.searchParams.get('state') === 'unacknowledged')).toBe(true);
  });

  it('replaces the detail of an alert that is gone and drops the row', async () => {
    serveAlerts([disruption]);
    server.use(respondProblem('get', '/api/v1/insights/disruption/{id}', 404, 'not-found'));
    await renderRoute(`/alerts?alert=${disruption.id}`);
    await listRegion().findByText(disruption.title);
    await waitFor(() => {
      expect(listRegion().queryByText(disruption.title)).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByText(en.alerts.gone)).toBeInTheDocument();
    });
  });

  it('offers the last 7 days for an alert that is not in the list', async () => {
    serveAlerts([disruption]);
    const { router } = await renderRoute('/alerts?alert=elsewhere', { as: 'viewer' });
    await userEvent.click(await screen.findByRole('button', { name: en.alerts.showLastWeek }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ window: '7d', state: 'all', alert: 'elsewhere' });
    });
  });

  it('moves through the list with j and k', async () => {
    serveAlerts([bunching, disruption]);
    const { router } = await renderRoute(`/alerts?alert=${bunching.id}`, { as: 'viewer' });
    await listRegion().findByText(disruption.title);
    await userEvent.keyboard('j');
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ alert: disruption.id });
    });
    await userEvent.keyboard('k');
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ alert: bunching.id });
    });
  });
});

describe('alert detail by type', () => {
  it('AC-8 bunching: both buses, the suggestion, and feedback for operators', async () => {
    serveAlerts([bunching]);
    const feedback: unknown[] = [];
    server.use(
      http.post(mswPath('/api/v1/insights/dispatch-suggestions/{id}/feedback'), async ({ request }) => {
        feedback.push(await request.json());
        return HttpResponse.json({
          ...getBunchingEpisode.examples['closed episode'].suggestion,
          operatorFeedback: 'accepted',
        });
      }),
    );
    await renderRoute(`/alerts?alert=${bunching.id}`, { as: 'operator' });
    const detail = await screen.findByRole('article', { name: bunching.title });
    expect(await within(detail).findByText(en.alerts.detailLabels.buses('1187', '1203'))).toBeInTheDocument();
    expect(within(detail).getByText(en.dispatchAction.full.hold_follower ?? '')).toBeInTheDocument();
    expect(within(detail).getByText(en.alerts.activity.suggestion('82%'))).toBeInTheDocument();

    await userEvent.click(within(detail).getByRole('button', { name: en.alerts.actions.accept }));
    await waitFor(() => {
      expect(notify.success).toHaveBeenCalledWith(en.alerts.feedbackSaved);
    });
    expect(feedback).toEqual([{ feedback: 'accepted' }]);
  });

  it('ticketing: refund numbers, previous windows and a link to the ticketing screen', async () => {
    serveAlerts([ticketing]);
    await renderRoute(`/alerts?alert=${ticketing.id}`, { as: 'viewer' });
    const detail = await screen.findByRole('article', { name: ticketing.title });
    expect(await within(detail).findByText(en.alerts.detailLabels.refundRate)).toBeInTheDocument();
    expect(within(detail).getByText(en.alerts.detailLabels.notClassified)).toBeInTheDocument();
    expect(within(detail).getByRole('link', { name: en.alerts.actions.openTicketing })).toHaveAttribute(
      'href',
      ticketing.link,
    );
  });

  it('AC-5 infrastructure: the runbook opens in a new tab, Grafana and labels are shown', async () => {
    serveAlerts([infra]);
    await renderRoute(`/alerts?alert=${infra.id}`, { as: 'viewer' });
    const detail = await screen.findByRole('article', { name: infra.title });
    const runbook = within(detail).getByRole('link', { name: en.alerts.actions.openRunbook });
    expect(runbook).toHaveAttribute('href', 'https://runbooks.example/kafka');
    expect(runbook).toHaveAttribute('target', '_blank');
    expect(runbook).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(within(detail).getByText('Broker 1 is unreachable')).toBeInTheDocument();
    expect(within(detail).getByText('KafkaBrokerDown')).toBeInTheDocument();
    expect(within(detail).getByRole('link', { name: en.alerts.actions.viewInGrafana })).toBeInTheDocument();
  });

  it('shows the AI analysis of a classified disruption to staff only', async () => {
    serveAlerts([disruption]);
    server.use(
      respond('get', '/api/v1/insights/disruption/{id}', {
        ...getDisruptionEpisode.examples.anonymous,
        likelyCause: 'traffic',
        causeConfidence: 0.68,
        enrichedAt: '2026-09-29T21:06:00Z',
        baselineMeanSeconds: 61,
      }),
    );
    await renderRoute(`/alerts?alert=${disruption.id}`, { as: 'viewer' });
    const detail = await screen.findByRole('article', { name: disruption.title });
    expect(await within(detail).findByText(en.likelyCause.traffic ?? '')).toBeInTheDocument();
    expect(within(detail).getByText(en.alerts.activity.classified('68%'))).toBeInTheDocument();
    expect(within(detail).getByText(en.alerts.detailLabels.delayVsNormal)).toBeInTheDocument();
  });
});
