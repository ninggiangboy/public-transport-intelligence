import type { InfiniteData } from '@tanstack/react-query';
import { InfiniteQueryObserver, QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import type { WithAsOf } from '@/api/client';
import type { components } from '@/api/generated/schema';
import { mergeDeadLetterHead, watchDeadLetterHeads } from '@/features/dlq/heads';
import { listQuery } from '@/features/dlq/queries';
import { mswPath } from '@/test/handlers';
import { server } from '@/test/server';
import { keys } from '@/api/keys';

type Item = components['schemas']['DeadLetterItemResponse'];
type Cached = InfiniteData<WithAsOf<{ items: Item[]; nextCursor?: string }>>;

const row = (id: string, createdAt: string, status = 'NEW'): Item => ({
  id,
  status,
  source: 'GTFS_RT_VEHICLE_POSITION',
  stage: 'QUALITY',
  errorClass: 'DQ-06',
  errorMessage: 'm',
  createdAt,
  updatedAt: createdAt,
  hasEditedPayload: false,
  replayCount: 0,
  autoReplayCount: 0,
});

const cache = (...pages: Item[][]): Cached => ({
  pageParams: pages.map((_, index) => (index === 0 ? undefined : `c${index}`)),
  pages: pages.map((items, index) => ({
    data: { items, ...(index < pages.length - 1 ? { nextCursor: `c${index + 1}` } : {}) },
    asOf: undefined,
  })),
});
const ids = (data: Cached | undefined) => data?.pages.flatMap((page) => page.data.items.map((item) => item.id));

describe('mergeDeadLetterHead', () => {
  it('puts new rows at the head, newest first, and leaves the older pages alone', () => {
    const cached = cache(
      [row('b', '2026-10-06T10:00:02Z'), row('a', '2026-10-06T10:00:01Z')],
      [row('z', '2026-10-06T09:00:00Z')],
    );
    const fresh = {
      items: [row('d', '2026-10-06T10:00:04Z'), row('c', '2026-10-06T10:00:03Z'), row('b', '2026-10-06T10:00:02Z')],
      nextCursor: 'next',
    };
    expect(ids(mergeDeadLetterHead(cached, fresh))).toEqual(['d', 'c', 'b', 'a', 'z']);
  });

  it('replaces a row where it stands, keeping its place', () => {
    const cached = cache([row('b', '2026-10-06T10:00:02Z'), row('a', '2026-10-06T10:00:01Z')]);
    const fresh = { items: [row('b', '2026-10-06T10:00:02Z', 'REPLAYED'), row('a', '2026-10-06T10:00:01Z')] };
    const merged = mergeDeadLetterHead(cached, fresh);
    expect(merged?.pages[0]?.data.items.map((item) => item.status)).toEqual(['REPLAYED', 'NEW']);
  });

  it('drops a row the first page would hold but does not (it left the filters)', () => {
    const cached = cache([
      row('c', '2026-10-06T10:00:03Z'),
      row('b', '2026-10-06T10:00:02Z'),
      row('a', '2026-10-06T10:00:01Z'),
    ]);
    // `b` is newer than the last fresh row, `a`, and absent: gone. `a` is not on the first page's span: kept.
    const fresh = { items: [row('c', '2026-10-06T10:00:03Z'), row('x', '2026-10-06T10:00:01.5Z')], nextCursor: 'next' };
    expect(ids(mergeDeadLetterHead(cached, fresh))).toEqual(['c', 'x', 'a']);
  });

  it('empties the list when the fresh list is whole and has nothing', () => {
    const cached = cache([row('a', '2026-10-06T10:00:01Z')]);
    expect(ids(mergeDeadLetterHead(cached, { items: [] }))).toEqual([]);
  });

  it('does nothing for a list that has not loaded', () => {
    expect(mergeDeadLetterHead(undefined, { items: [row('a', '2026-10-06T10:00:01Z')] })).toBeUndefined();
  });
});

describe('watchDeadLetterHeads', () => {
  const wait = () => new Promise((resolve) => setTimeout(resolve, 50));

  function serve(rows: () => Item[]) {
    const requests: URL[] = [];
    server.use(
      http.get(mswPath('/api/v1/etl/dlq'), ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json({ items: rows() });
      }),
    );
    return requests;
  }

  it('loads the first page of a list on screen when it is marked stale, and merges it', async () => {
    let rows = [row('a', '2026-10-06T10:00:01Z')];
    const requests = serve(() => rows);
    const queryClient = new QueryClient();
    const stop = watchDeadLetterHeads(queryClient);
    const observer = new InfiniteQueryObserver(queryClient, listQuery({ status: ['NEW'] }));
    const unsubscribe = observer.subscribe(() => undefined);
    await wait();
    expect(requests).toHaveLength(1);

    rows = [row('b', '2026-10-06T10:00:02Z'), row('a', '2026-10-06T10:00:01Z')];
    await queryClient.invalidateQueries({ queryKey: keys.etl.dlq.listAll(), refetchType: 'none' });
    await wait();
    expect(requests).toHaveLength(2);
    expect(requests[1]?.searchParams.get('limit')).toBe('200');
    expect(requests[1]?.searchParams.getAll('status')).toEqual(['NEW']);
    expect(ids(queryClient.getQueryData(listQuery({ status: ['NEW'] }).queryKey))).toEqual(['b', 'a']);
    unsubscribe();
    stop();
  });

  it('leaves a list nobody watches, and the lists of other queries, alone', async () => {
    const requests = serve(() => []);
    const queryClient = new QueryClient();
    const stop = watchDeadLetterHeads(queryClient);
    queryClient.setQueryData(listQuery({ status: ['NEW'] }).queryKey, cache([row('a', '2026-10-06T10:00:01Z')]));
    queryClient.setQueryData(keys.etl.jobs.list({ status: ['FAILED'] }), { pages: [] });
    await queryClient.invalidateQueries({ queryKey: ['etl'], refetchType: 'none' });
    await wait();
    expect(requests).toHaveLength(0);
    stop();
  });
});
