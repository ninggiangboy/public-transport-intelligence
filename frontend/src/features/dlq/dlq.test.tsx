import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { components } from '@/api/generated/schema';
import type { DeadLetter, DeadLetterDetail } from '@/features/dlq/model';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { setDeadLetterStatus } from '@/realtime/handlers';
import { mswPath } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

const copy = dlqCopy.dlq;
type Replay = components['schemas']['ReplayResponse'];

const ID = (n: number) => `0192f5a${n}-7c1e-7d3a-9b2c-4e5f6a7b8c9d`;
const at = (second: number) => `2026-10-06T10:00:${String(second).padStart(2, '0')}Z`;

function item(n: number, over: Partial<DeadLetter> = {}): DeadLetter {
  return {
    id: ID(n),
    source: 'GTFS_RT_VEHICLE_POSITION',
    stage: 'QUALITY',
    ruleId: 'DQ-06',
    errorClass: 'DQ-06',
    errorMessage: `position ${n} is outside the service area`,
    status: 'NEW',
    category: 'schema_violation',
    categoryConfidence: 0.41,
    severity: 2,
    severityConfidence: 0.66,
    createdAt: at(60 - n),
    updatedAt: at(60 - n),
    hasEditedPayload: false,
    replayCount: 0,
    autoReplayCount: 0,
    ...over,
  };
}

function detailOf(row: DeadLetter, allowed: string[], over: Partial<DeadLetterDetail> = {}): DeadLetterDetail {
  return {
    ...row,
    actions: [
      { action: 'TRIAGED', actor: 'auto', at: at(2), confidence: 0.41, details: {} },
      { action: 'MANUAL_REQUIRED', actor: 'auto', at: at(3), details: {} },
    ],
    allowedActions: allowed,
    batchId: '0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d',
    kafka: { topic: 'gtfs.vehicle_positions', partition: 3, offset: 18_204_771, timestamp: at(1) },
    modelVersion: 'jev@0.2.0',
    triagedAt: at(2),
    triageAttempts: 1,
    rawPayload: JSON.stringify({ vehicle: { id: '1742' }, position: { latitude: 99, longitude: -93.2 } }),
    replays: [],
    ...over,
  };
}

const OPERATOR = ['edit', 'replay', 'discard'];

interface Served {
  lists: URL[];
  posts: { path: string; key: string | null; body: unknown }[];
  puts: { text: string; type: string | null }[];
}

/** E-40 by status, E-42 by id, and `summary`/`actions` left to the default examples. */
function serve(rows: DeadLetter[], details: Record<string, DeadLetterDetail> = {}): Served {
  const served: Served = { lists: [], posts: [], puts: [] };
  server.use(
    http.get(mswPath('/api/v1/etl/dlq'), ({ request }) => {
      const url = new URL(request.url);
      served.lists.push(url);
      const statuses = url.searchParams.getAll('status');
      return HttpResponse.json({ items: rows.filter((row) => statuses.length === 0 || statuses.includes(row.status)) });
    }),
    http.get(mswPath('/api/v1/etl/dlq/:id'), ({ params }) => {
      if (params.id === 'summary' || params.id === 'actions') return undefined;
      const found = details[String(params.id)];
      const row = rows.find((candidate) => candidate.id === params.id);
      if (found) return HttpResponse.json(found);
      if (row) return HttpResponse.json(detailOf(row, []));
      return HttpResponse.json(
        { type: 'urn:pti:problem:not-found', title: 'Not found', status: 404, traceId: 'trace' },
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }),
  );
  return served;
}

const replayResponse = (id = 'rep-1'): Replay => ({
  id,
  kind: 'DLQ_RECORD',
  source: 'GTFS_RT_VEHICLE_POSITION',
  status: 'PENDING',
  requestedAt: at(30),
  requestedBy: 'user:operator',
});

/** Records the POST of `/dlq/{id}/{action}` and answers like the API. */
function servePosts(served: Served, options: { wait?: number; fail?: Record<string, number> } = {}) {
  let running = 0;
  const peak = { value: 0 };
  server.use(
    http.post(mswPath('/api/v1/etl/dlq/:id/:action'), async ({ request, params }) => {
      running += 1;
      peak.value = Math.max(peak.value, running);
      const text = await request.text();
      served.posts.push({
        path: String(params.action),
        key: request.headers.get('Idempotency-Key'),
        body: text ? (JSON.parse(text) as unknown) : undefined,
      });
      if (options.wait) await delay(options.wait);
      running -= 1;
      const status = options.fail?.[String(params.id)];
      if (status) {
        return HttpResponse.json(
          {
            type: 'urn:pti:problem:dlq-invalid-state',
            title: 'Invalid state',
            status,
            traceId: 'trace',
            currentStatus: 'REPLAYED',
          },
          { status, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      return params.action === 'replay' || params.action === 'confirm'
        ? HttpResponse.json(replayResponse(), { status: 202 })
        : HttpResponse.json(detailOf(item(1, { status: 'DISCARDED' }), []));
    }),
  );
  return peak;
}

beforeEach(() => {
  for (const fn of Object.values(notify)) fn.mockClear();
  // jsdom lays nothing out: the list is 600 px high and a row 96 px, so that the virtualiser has rows to draw.
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(600);
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(400);
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    const height = this.tagName === 'TR' ? 96 : 0;
    return { x: 0, y: 0, top: 0, left: 0, right: 400, bottom: height, width: 400, height, toJSON: () => ({}) };
  });
});

const rowText = (n: number) => `position ${n} is outside the service area`;

describe('Dead letters', () => {
  it('AC-5 shows a viewer the records read-only: no checkbox, no action button', async () => {
    serve([item(1), item(2)], { [ID(1)]: detailOf(item(1), []) });
    await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'viewer' });
    expect((await screen.findAllByText(copy.readOnly)).length).toBeGreaterThan(0);
    expect(await screen.findByText(rowText(2))).toBeInTheDocument();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    const detail = await screen.findByRole('article', { name: copy.detail });
    expect(within(detail).queryByRole('button', { name: copy.buttons.replay })).not.toBeInTheDocument();
    expect(within(detail).queryByRole('button', { name: copy.buttons.discard })).not.toBeInTheDocument();
    expect(within(detail).queryByText(copy.detailPanel.noActions('New'))).not.toBeInTheDocument();
  });

  it('shows the summary, the tab counts and the strip that leads to Confirm', async () => {
    serve([item(1)]);
    const { router } = await renderRoute('/ops/dlq', { as: 'viewer' });
    expect(await screen.findByText(copy.summary('214', '37'))).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Confirm\s*9$/ })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: copy.confirmBanner.review }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ tab: 'confirm' });
    });
    expect(await screen.findByText(copy.confirmBanner.suggested)).toBeInTheDocument();
  });

  it('writes a filter to the URL without a new history entry and asks E-40 with it', async () => {
    const served = serve([item(1), item(2, { source: 'TICKETING_SALES' })]);
    const { router } = await renderRoute('/ops/dlq', { as: 'viewer' });
    await screen.findByText(rowText(1));
    const entries = router.history.length;
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: copy.filters.source }));
    await user.click(await screen.findByRole('checkbox', { name: en.source.TICKETING_SALES }));
    await waitFor(() => {
      expect([router.state.location.search.source].flat()).toEqual(['TICKETING_SALES']);
    });
    expect(router.history.length).toBe(entries);
    await waitFor(() => {
      expect(served.lists.at(-1)?.searchParams.getAll('source')).toEqual(['TICKETING_SALES']);
    });
    expect(served.lists.at(-1)?.searchParams.getAll('status')).toContain('MANUAL');
  });

  it('opens the record of a row as a new history entry', async () => {
    serve([item(1), item(2)]);
    const { router } = await renderRoute('/ops/dlq', { as: 'viewer' });
    await userEvent.setup().click(await screen.findByText(rowText(2)));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ id: ID(2) });
    });
    expect(router.history.length).toBeGreaterThan(1);
    expect(await screen.findByRole('article', { name: copy.detail })).toBeInTheDocument();
  });

  it('moves between records with j and k', async () => {
    serve([item(1), item(2), item(3)]);
    const { router } = await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'viewer' });
    await screen.findByText(rowText(3));
    fireEvent.keyDown(document.body, { key: 'j' });
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ id: ID(2) });
    });
    fireEvent.keyDown(document.body, { key: 'k' });
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ id: ID(1) });
    });
  });

  it('says a swept record no longer exists', async () => {
    serve([item(1)]);
    await renderRoute('/ops/dlq?id=0192f5a9-0000-7000-8000-000000000000', { as: 'viewer' });
    expect(await screen.findByText(copy.gone)).toBeInTheDocument();
  });

  it('AC-8 changes the status of a row in place when somebody else moves it', async () => {
    serve([item(1), item(2)]);
    const { queryClient } = await renderRoute('/ops/dlq', { as: 'viewer' });
    await screen.findByText(rowText(2));
    const before = screen.getAllByRole('row').map((row) => row.textContent);
    const row = (await screen.findByText(rowText(2))).closest('tr');
    setDeadLetterStatus(queryClient, ID(2), 'MANUAL');
    await waitFor(() => {
      expect(row).toHaveTextContent(en.status.dlq.MANUAL);
    });
    expect(screen.getAllByRole('row').map((each) => each.textContent.replace(en.status.dlq.MANUAL, ''))).toEqual(
      before,
    );
  });

  describe('as an operator', () => {
    it('AC-2 turns the record into "Replay requested" at once, with a key, and says so', async () => {
      const served = serve([item(1)], { [ID(1)]: detailOf(item(1), OPERATOR) });
      servePosts(served, { wait: 500 });
      await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
      const user = userEvent.setup();
      await user.click(await screen.findByRole('button', { name: copy.buttons.replay }));
      const dialog = await screen.findByRole('dialog', { name: copy.dialogs.replayTitle });
      expect(within(dialog).getByText(copy.dialogs.replayOriginal)).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: copy.buttons.replay }));
      // The answer takes 500 ms; the status is already there.
      expect((await screen.findAllByText(en.status.dlq.REPLAY_REQUESTED)).length).toBeGreaterThan(0);
      expect(notify.success.mock.calls).toEqual([]);
      await waitFor(() => {
        expect(notify.success).toHaveBeenCalledWith(copy.toast.requestSent, expect.anything());
      });
      expect(served.posts[0]).toMatchObject({
        path: 'replay',
        key: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      });
    });

    it('puts the status back and tells why when the record moved on (409)', async () => {
      const served = serve([item(1)], { [ID(1)]: detailOf(item(1), OPERATOR) });
      servePosts(served, { fail: { [ID(1)]: 409 } });
      await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
      const user = userEvent.setup();
      await user.click(await screen.findByRole('button', { name: copy.buttons.replay }));
      const dialog = await screen.findByRole('dialog', { name: copy.dialogs.replayTitle });
      await user.click(within(dialog).getByRole('button', { name: copy.buttons.replay }));
      await waitFor(() => {
        expect(notify.error).toHaveBeenCalledWith(en.error.slug['dlq-invalid-state'].title(), expect.anything());
      });
      const detail = await screen.findByRole('article', { name: copy.detail });
      expect(within(detail).queryByText(en.status.dlq.REPLAY_REQUESTED)).not.toBeInTheDocument();
    });

    it('tells a record that replays and one that fails again', async () => {
      const served = serve([item(1)], { [ID(1)]: detailOf(item(1, { status: 'REPLAY_REQUESTED' }), []) });
      servePosts(served);
      const { queryClient } = await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
      await screen.findByRole('article', { name: copy.detail });
      server.use(
        http.get(mswPath('/api/v1/etl/dlq/:id'), ({ params }) =>
          params.id === ID(1) ? HttpResponse.json(detailOf(item(1, { status: 'REPLAYED' }), [])) : undefined,
        ),
      );
      await queryClient.invalidateQueries({ queryKey: ['etl', 'dlq', 'detail', ID(1)] });
      await waitFor(() => {
        expect(notify.success).toHaveBeenCalledWith(copy.toast.replayed);
      });
    });

    it('AC-9 sends "Test data: load test" and keeps Discard disabled for "Other" without a note', async () => {
      const served = serve([item(1)], { [ID(1)]: detailOf(item(1), OPERATOR) });
      servePosts(served);
      await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
      const user = userEvent.setup();
      await user.click(await screen.findByRole('button', { name: copy.buttons.discard }));
      const dialog = await screen.findByRole('dialog', { name: copy.dialogs.discardTitle });
      expect(within(dialog).getByText(copy.dialogs.discardBody)).toBeInTheDocument();
      const discard = within(dialog).getByRole('button', { name: copy.buttons.discardRecord });
      await user.click(within(dialog).getByRole('radio', { name: copy.dialogs.reasons.other }));
      expect(discard).toBeDisabled();
      await user.click(within(dialog).getByRole('radio', { name: copy.dialogs.reasons.test }));
      await user.type(within(dialog).getByLabelText(copy.dialogs.noteOptional), 'load test');
      expect(discard).toBeEnabled();
      await user.click(discard);
      await waitFor(() => {
        expect(served.posts[0]).toMatchObject({ path: 'discard', body: { reason: 'Test data: load test' } });
      });
      await waitFor(() => {
        expect(notify.success).toHaveBeenCalledWith(copy.toast.discarded);
      });
    });

    it('resolves a manual record with a note', async () => {
      const row = item(1, { status: 'MANUAL' });
      const served = serve([row], { [ID(1)]: detailOf(row, ['resolve', 'edit', 'replay', 'discard']) });
      servePosts(served);
      await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
      const user = userEvent.setup();
      await user.click(await screen.findByRole('button', { name: copy.buttons.resolve }));
      const dialog = await screen.findByRole('dialog', { name: copy.dialogs.resolveTitle });
      await user.type(within(dialog).getByLabelText(copy.dialogs.note), 'fixed in the warehouse');
      await user.click(within(dialog).getByRole('button', { name: copy.buttons.resolve }));
      await waitFor(() => {
        expect(served.posts[0]).toMatchObject({ path: 'resolve', body: { note: 'fixed in the warehouse' } });
      });
    });

    it('AC-4 confirms five records, four requests at a time, each with its own key, and counts them', async () => {
      const rows = [1, 2, 3, 4, 5].map((n) => item(n, { status: 'PENDING_CONFIRM' }));
      const served = serve(rows);
      const peak = servePosts(served, { wait: 40, fail: { [ID(5)]: 409 } });
      await renderRoute('/ops/dlq?tab=confirm', { as: 'operator' });
      const user = userEvent.setup();
      await screen.findByText(rowText(5));
      await user.click(screen.getByRole('checkbox', { name: copy.selectAll }));
      const bar = await screen.findByRole('region', { name: copy.selected(5) });
      await user.click(within(bar).getByRole('button', { name: copy.bulk.confirmReplay }));
      const dialog = await screen.findByRole('dialog', { name: copy.bulk.confirmTitle(5) });
      await user.click(within(dialog).getByRole('button', { name: copy.bulk.confirmReplay }));
      await waitFor(() => {
        expect(notify.warning).toHaveBeenCalledWith(copy.bulk.confirmed(4, 1));
      });
      expect(peak.value).toBeLessThanOrEqual(4);
      expect(served.posts).toHaveLength(5);
      expect(new Set(served.posts.map((post) => post.key)).size).toBe(5);
      expect(served.posts.every((post) => post.path === 'confirm')).toBe(true);
      // The one that failed keeps its selection and says so on its row.
      expect(await screen.findByRole('region', { name: copy.selected(1) })).toBeInTheDocument();
      expect(screen.getByLabelText(copy.rowFailed)).toBeInTheDocument();
    });

    it('lets at most 100 records be selected and says so', async () => {
      const rows = Array.from({ length: 120 }, (_, index) => item(index + 1));
      serve(rows);
      await renderRoute('/ops/dlq', { as: 'operator' });
      await screen.findByText(rowText(1));
      await userEvent.setup().click(screen.getByRole('checkbox', { name: copy.selectAll }));
      expect(await screen.findByRole('region', { name: copy.selected(100) })).toBeInTheDocument();
      expect(notify.message).toHaveBeenCalledWith(copy.bulk.limit);
    });

    describe('the payload editor', () => {
      async function openEditor(served: Served, extra: Partial<DeadLetterDetail> = {}) {
        server.use(
          http.put(mswPath('/api/v1/etl/dlq/:id/payload'), async ({ request }) => {
            const text = await request.text();
            served.puts.push({ text, type: request.headers.get('Content-Type') });
            if (text.includes('"abc"')) {
              return HttpResponse.json(
                {
                  type: 'urn:pti:problem:invalid-payload',
                  title: 'Invalid payload',
                  status: 422,
                  traceId: 'trace',
                  errors: [{ field: '/position/latitude', message: 'must be a number' }],
                },
                { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
              );
            }
            return HttpResponse.json(
              detailOf(item(1, { hasEditedPayload: true }), OPERATOR, {
                editedPayload: JSON.parse(text) as Record<string, unknown>,
                ...extra,
              }),
            );
          }),
        );
        const user = userEvent.setup();
        await user.click(await screen.findByRole('button', { name: copy.buttons.edit }));
        return { user, editor: await screen.findByRole('textbox', { name: copy.editor.label }) };
      }
      const change = (editor: HTMLElement, value: string) => {
        fireEvent.change(editor, { target: { value } });
      };

      it('AC-3 pins the errors of the API to the line of the field and keeps the text', async () => {
        const served = serve([item(1)], { [ID(1)]: detailOf(item(1), OPERATOR) });
        await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
        const { user, editor } = await openEditor(served);
        const save = screen.getByRole('button', { name: copy.buttons.save });
        expect(save).toBeDisabled();
        expect(screen.getByText(copy.editor.valid)).toBeInTheDocument();

        change(editor, '{"position": {"latitude": "abc"}}');
        change(editor, '{"position": {"latitude": ');
        expect(await screen.findByText(/^Not valid JSON/)).toBeInTheDocument();
        expect(save).toBeDisabled();

        change(editor, JSON.stringify({ position: { latitude: 'abc' } }, null, 2));
        expect(save).toBeEnabled();
        await user.click(save);
        const diagnostics = await screen.findByRole('list', { name: 'Editor diagnostics' });
        expect(within(diagnostics).getByText('3: must be a number')).toBeInTheDocument();
        expect(screen.getByRole('alert')).toHaveTextContent(copy.editor.errors);
        expect(served.puts[0]?.type).toContain('application/json');
        expect(editor).toHaveValue(JSON.stringify({ position: { latitude: 'abc' } }, null, 2));
        expect(screen.getByRole('button', { name: copy.buttons.save })).toBeInTheDocument();
      });

      it('AC-2 saves the payload as it is, shows the diff and replays it', async () => {
        const served = serve([item(1)], { [ID(1)]: detailOf(item(1), OPERATOR) });
        servePosts(served);
        await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
        const { user, editor } = await openEditor(served);
        const fixed = JSON.stringify(
          { vehicle: { id: '1742' }, position: { latitude: 44.97, longitude: -93.27 } },
          null,
          2,
        );
        change(editor, fixed);
        await user.click(screen.getByRole('button', { name: copy.buttons.saveAndReplay }));
        await waitFor(() => {
          expect(served.puts[0]?.text).toBe(fixed);
        });
        expect(notify.success).toHaveBeenCalledWith(copy.toast.saved);
        const dialog = await screen.findByRole('dialog', { name: copy.dialogs.replayTitle });
        expect(within(dialog).getByText(copy.dialogs.replayEdited)).toBeInTheDocument();
        expect(screen.getByRole('radio', { name: copy.detailPanel.edited, hidden: true })).toBeChecked();
        await user.click(within(dialog).getByRole('button', { name: copy.buttons.replay }));
        await waitFor(() => {
          expect(served.posts[0]).toMatchObject({ path: 'replay' });
        });
      });

      it('AC-7 asks before another record replaces an editor with changes', async () => {
        const served = serve([item(1), item(2)], { [ID(1)]: detailOf(item(1), OPERATOR) });
        const { router } = await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
        const { user, editor } = await openEditor(served);
        change(editor, '{"changed": true}');

        fireEvent.keyDown(document.body, { key: 'j' });
        const dialog = await screen.findByRole('dialog', { name: copy.dialogs.discardChangesTitle });
        await user.click(within(dialog).getByRole('button', { name: copy.dialogs.keepEditing }));
        expect(router.state.location.search).toMatchObject({ id: ID(1) });
        expect(screen.getByRole('textbox', { name: copy.editor.label })).toHaveValue('{"changed": true}');

        await user.click(screen.getByText(rowText(2)));
        const again = await screen.findByRole('dialog', { name: copy.dialogs.discardChangesTitle });
        await user.click(within(again).getByRole('button', { name: copy.dialogs.discardChanges }));
        await waitFor(() => {
          expect(router.state.location.search).toMatchObject({ id: ID(2) });
        });
      });

      it('does not let a key typed in the editor move the selection', async () => {
        const served = serve([item(1), item(2)], { [ID(1)]: detailOf(item(1), OPERATOR) });
        const { router } = await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
        const { editor } = await openEditor(served);
        fireEvent.keyDown(editor, { key: 'j' });
        expect(router.state.location.search).toMatchObject({ id: ID(1) });
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      });

      it('asks before Cancel throws away changes, and not otherwise', async () => {
        const served = serve([item(1)], { [ID(1)]: detailOf(item(1), OPERATOR) });
        await renderRoute(`/ops/dlq?id=${ID(1)}`, { as: 'operator' });
        const { user, editor } = await openEditor(served);
        await user.click(screen.getByRole('button', { name: copy.buttons.cancel }));
        expect(screen.queryByRole('textbox', { name: copy.editor.label })).not.toBeInTheDocument();

        await user.click(await screen.findByRole('button', { name: copy.buttons.edit }));
        change(await screen.findByRole('textbox', { name: copy.editor.label }), '{"x": 1}');
        await user.click(screen.getByRole('button', { name: copy.buttons.cancel }));
        expect(await screen.findByRole('dialog', { name: copy.dialogs.discardChangesTitle })).toBeInTheDocument();
        expect(editor).toBeDefined();
      });
    });
  });

  it('AC-6 asks the action log for what auto-triage did, with its confidence', async () => {
    const requests: URL[] = [];
    serve([item(1)]);
    server.use(
      http.get(mswPath('/api/v1/etl/dlq/actions'), ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json({
          items: [
            {
              id: 1,
              action: 'CONFIRM_REQUESTED',
              actor: 'auto',
              at: at(5),
              confidence: 0.88,
              deadLetterId: ID(1),
              details: {},
              source: 'GTFS_RT_VEHICLE_POSITION',
              status: 'PENDING_CONFIRM',
            },
          ],
        });
      }),
    );
    await renderRoute('/ops/dlq?tab=actions&actor=auto', { as: 'viewer' });
    const table = await screen.findByRole('table', { name: copy.actionsCaption });
    expect(await within(table).findByText(copy.action.CONFIRM_REQUESTED ?? '')).toBeInTheDocument();
    expect(within(table).getByText(copy.autoTriage)).toBeInTheDocument();
    expect(within(table).getByText(/88%/)).toBeInTheDocument();
    expect(requests[0]?.searchParams.get('actorType')).toBe('auto');
  });
});
