import { describe, expect, it } from 'vitest';

import {
  accepts,
  actionFilters,
  actorLabel,
  detailsSummary,
  discardReasonText,
  discardValid,
  editorText,
  errorCode,
  isFiltered,
  listFilters,
  listStatuses,
  MAX_SELECTED,
  routingDecision,
  runBulk,
  sameJson,
  select,
  showsStatus,
} from '@/features/dlq/model';
import { dlqSearch } from '@/features/dlq/search';

const parse = (search: Record<string, unknown>) => dlqSearch.parse(search);

describe('what a tab lists', () => {
  it('lists the open statuses on Review, narrowed by the Status chip inside the group', () => {
    expect(listStatuses('review', undefined)).toContain('PENDING_CONFIRM');
    expect(listStatuses('review', ['MANUAL', 'REPLAYED'])).toEqual(['MANUAL']);
    expect(listStatuses('closed', ['MANUAL'])).toEqual(['REPLAYED', 'DISCARDED', 'RESOLVED']);
  });

  it('fixes Confirm to PENDING_CONFIRM and gives it Source and Category only', () => {
    const filters = listFilters(
      'confirm',
      parse({ source: 'TICKETING_SALES', category: ['unknown'], stage: ['LOAD'], severity: [2], status: ['NEW'] }),
    );
    expect(filters).toEqual({ status: ['PENDING_CONFIRM'], source: ['TICKETING_SALES'], category: ['unknown'] });
  });

  it('sends severity as strings and the rule as ruleId', () => {
    const filters = listFilters('review', parse({ severity: ['2', '0'], rule: 'DQ-06', from: '2026-10-06T00:00:00Z' }));
    expect(filters).toMatchObject({ severity: ['2', '0'], ruleId: ['DQ-06'], from: '2026-10-06T00:00:00Z' });
  });

  it('knows when a filter beyond the tab is on', () => {
    expect(isFiltered('review', parse({}))).toBe(false);
    expect(isFiltered('review', parse({ status: ['REPLAYED'] }))).toBe(false);
    expect(isFiltered('review', parse({ stage: ['LOAD'] }))).toBe(true);
    expect(isFiltered('actions', parse({ actor: 'auto' }))).toBe(true);
  });

  it('asks E-48 for the window, ending now', () => {
    const now = Date.parse('2026-10-06T12:00:00Z');
    expect(actionFilters(parse({ actor: 'auto', window: '7d' }), now)).toEqual({
      actorType: 'auto',
      from: '2026-09-29T12:00:00.000Z',
      to: '2026-10-06T12:00:00.000Z',
    });
  });

  it('drops values of the URL it cannot read', () => {
    expect(parse({ tab: 'nope', severity: ['9'], rule: 'x', actor: 'robot' })).toMatchObject({
      tab: undefined,
      severity: undefined,
      rule: undefined,
      actor: undefined,
    });
  });
});

describe('rows', () => {
  it('names a row by its rule, or by the short name of its exception', () => {
    expect(errorCode({ ruleId: 'DQ-01', errorClass: 'SchemaViolationException' })).toBe('DQ-01');
    expect(errorCode({ errorClass: 'com.fasterxml.JsonParseException' })).toBe('JsonParseException');
  });

  it('shows the status on a row unless it is a plain New one or the tab is Confirm', () => {
    expect(showsStatus('review', 'NEW')).toBe(false);
    expect(showsStatus('review', 'MANUAL')).toBe(true);
    expect(showsStatus('closed', 'REPLAYED')).toBe(true);
    expect(showsStatus('confirm', 'PENDING_CONFIRM')).toBe(false);
  });
});

describe('selection and bulk', () => {
  it('stops at 100 and says it was capped', () => {
    const ids = Array.from({ length: 150 }, (_, index) => `d${index}`);
    const { selected, capped } = select([], ids);
    expect(selected).toHaveLength(MAX_SELECTED);
    expect(capped).toBe(true);
    expect(select(['d0'], ['d0']).capped).toBe(false);
  });

  it('lets each action take the statuses of DOC-31 §7', () => {
    expect(accepts('replay', 'NEW')).toBe(true);
    expect(accepts('replay', 'PENDING_CONFIRM')).toBe(false);
    expect(accepts('confirm', 'PENDING_CONFIRM')).toBe(true);
    expect(accepts('discard', 'PENDING_CONFIRM')).toBe(true);
    expect(accepts('discard', 'REPLAYED')).toBe(false);
  });

  it('runs at most four at a time, tells progress and collects the failures', async () => {
    let running = 0;
    let most = 0;
    const progress: number[] = [];
    const outcome = await runBulk(
      ['a', 'b', 'c', 'd', 'e', 'f'],
      async (id) => {
        running += 1;
        most = Math.max(most, running);
        await new Promise((resolve) => setTimeout(resolve, 5));
        running -= 1;
        if (id === 'c') throw new Error('boom');
      },
      (done) => progress.push(done),
    );
    expect(most).toBe(4);
    expect(outcome.ok).toHaveLength(5);
    expect(outcome.failed.map((failure) => failure.id)).toEqual(['c']);
    expect(progress.at(-1)).toBe(6);
  });
});

describe('discard', () => {
  it('writes the choice, then the note', () => {
    expect(discardReasonText('test', 'load test')).toBe('Test data: load test');
    expect(discardReasonText('duplicate', '  ')).toBe('Duplicate of a processed record');
  });

  it('wants a note of 3 characters for "Other" and keeps the text within 500', () => {
    expect(discardValid('other', '')).toBe(false);
    expect(discardValid('other', 'ab')).toBe(false);
    expect(discardValid('other', 'abc')).toBe(true);
    expect(discardValid('duplicate', '')).toBe(true);
    expect(discardValid('test', 'x'.repeat(500))).toBe(false);
  });
});

describe('detail', () => {
  it('reads who acted', () => {
    expect(actorLabel('auto')).toBe('Auto-triage');
    expect(actorLabel('system:replay-worker')).toBe('System (replay-worker)');
    expect(actorLabel('user:operator')).toBe('operator');
    expect(actorLabel('migration')).toBe('System');
  });

  it('takes the routing decision from the last triage action', () => {
    expect(
      routingDecision([
        { action: 'TRIAGED', at: '2026-10-06T10:00:00Z' },
        { action: 'CONFIRM_REQUESTED', at: '2026-10-06T10:00:01Z' },
        { action: 'EDITED', at: '2026-10-06T10:05:00Z' },
      ]),
    ).toBe('Sent for confirmation');
    expect(routingDecision([{ action: 'TRIAGED', at: '2026-10-06T10:00:00Z' }])).toBeUndefined();
  });

  it('sums up details on one line', () => {
    expect(
      detailsSummary({ reason: 'Test data', changed_paths: ['/a', '/b'], replay_request_id: '0192f5a1-7c1e' }),
    ).toBe('Test data · /a, /b · replay 0192f5a1');
    expect(detailsSummary({})).toBe('');
  });

  it('opens the editor with the edited payload, else the raw one, pretty', () => {
    expect(editorText({ rawPayload: '{"a":1}' })).toBe('{\n  "a": 1\n}');
    expect(editorText({ rawPayload: '{"a":1}', editedPayload: { a: 2 } })).toBe('{\n  "a": 2\n}');
    expect(editorText({ rawPayload: 'not json' })).toBe('not json');
  });

  it('compares JSON by value, not by formatting', () => {
    expect(sameJson('{"a":1}', '{\n  "a": 1\n}')).toBe(true);
    expect(sameJson('{"a":1}', '{"a":2}')).toBe(false);
  });
});
