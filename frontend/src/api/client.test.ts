import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { api, type AuthAdapter, read, setAuth, write } from '@/api/client';
import { listRoutes } from '@/api/generated/examples';
import { ApiError, isApiError } from '@/api/problem';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { server } from '@/test/server';

function fakeAuth(tokens: string[], renewResults: boolean[] = []) {
  let current = tokens.shift();
  const renew = vi.fn(() => {
    const ok = renewResults.shift() ?? false;
    if (ok) current = tokens.shift();
    return Promise.resolve(ok);
  });
  const expired = vi.fn();
  const adapter: AuthAdapter = { accessToken: () => current, renew, expired };
  setAuth(adapter);
  return { renew, expired };
}

afterEach(() => {
  setAuth(undefined);
});

describe('read', () => {
  it('returns the body of an operation served from its openapi.json example', async () => {
    const { data } = await read(api.GET('/api/v1/routes'));
    expect(data).toEqual(listRoutes.examples.routes);
  });

  it('passes X-Data-As-Of along with the body', async () => {
    server.use(
      respond(
        'get',
        '/api/v1/routes',
        { feedVersionId: 3, items: [] },
        { headers: { 'X-Data-As-Of': '2026-09-29T21:14:00Z' } },
      ),
    );
    expect(await read(api.GET('/api/v1/routes'))).toEqual({
      data: { feedVersionId: 3, items: [] },
      asOf: '2026-09-29T21:14:00Z',
    });
  });

  it('throws ApiError with the Problem Details and its slug', async () => {
    server.use(respondProblem('get', '/api/v1/stops/{stopId}', 404, 'not-found'));
    const error: unknown = await read(
      api.GET('/api/v1/stops/{stopId}', { params: { path: { stopId: 'nope' } } }),
    ).catch((e: unknown) => e);
    expect(isApiError(error)).toBe(true);
    expect(error).toMatchObject({ status: 404, slug: 'not-found', traceId: '4bf92f3577b34da6a3ce929d0e0e4736' });
  });

  it('builds a stand-in problem when the error body is not Problem Details', async () => {
    server.use(
      http.get(mswPath('/api/v1/routes'), () =>
        HttpResponse.text('Bad Gateway', { status: 502, statusText: 'Bad Gateway', headers: { 'X-Trace-Id': 'abc' } }),
      ),
    );
    const error = (await read(api.GET('/api/v1/routes')).catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.slug).toBeUndefined();
    expect(error.problem.type).toBe('about:blank');
    expect(error.traceId).toBe('abc');
  });
});

describe('authorization', () => {
  it('sends no Authorization header when anonymous', async () => {
    let header: string | null = 'unset';
    server.use(
      http.get(mswPath('/api/v1/me'), ({ request }) => {
        header = request.headers.get('Authorization');
        return HttpResponse.json({ authenticated: false, roles: [] });
      }),
    );
    await read(api.GET('/api/v1/me'));
    expect(header).toBeNull();
  });

  it('UX-06: renews the token once after a 401 and sends the request again', async () => {
    const auth = fakeAuth(['old', 'new'], [true]);
    const seen: (string | null)[] = [];
    server.use(
      http.post(mswPath('/api/v1/alerts/{id}/ack'), ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return request.headers.get('Authorization') === 'Bearer new'
          ? HttpResponse.json({ ok: true })
          : HttpResponse.json(
              { type: 'urn:pti:problem:unauthorized', title: 'Unauthorized', status: 401, traceId: 't' },
              { status: 401 },
            );
      }),
    );

    await write(api.POST('/api/v1/alerts/{id}/ack', { params: { path: { id: 'a1' } } }));

    expect(seen).toEqual(['Bearer old', 'Bearer new']);
    expect(auth.renew).toHaveBeenCalledTimes(1);
    expect(auth.expired).not.toHaveBeenCalled();
  });

  it('UX-06: gives up after one retry when the renewed token is rejected too', async () => {
    const auth = fakeAuth(['old', 'new'], [true, true]);
    let calls = 0;
    server.use(
      http.get(mswPath('/api/v1/me'), () => {
        calls += 1;
        return HttpResponse.json(
          { type: 'urn:pti:problem:unauthorized', title: 'Unauthorized', status: 401, traceId: 't' },
          { status: 401 },
        );
      }),
    );

    const error = (await read(api.GET('/api/v1/me')).catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(401);
    expect(calls).toBe(2);
    expect(auth.renew).toHaveBeenCalledTimes(1);
    expect(auth.expired).toHaveBeenCalledTimes(1);
  });

  it('marks the session expired when the silent renewal fails', async () => {
    const auth = fakeAuth(['old'], [false]);
    server.use(
      http.get(mswPath('/api/v1/me'), () =>
        HttpResponse.json(
          { type: 'urn:pti:problem:unauthorized', title: 'Unauthorized', status: 401, traceId: 't' },
          { status: 401 },
        ),
      ),
    );
    await expect(read(api.GET('/api/v1/me'))).rejects.toBeInstanceOf(ApiError);
    expect(auth.expired).toHaveBeenCalledTimes(1);
  });
});

describe('Idempotency-Key (DOC-31 §8)', () => {
  function captureKey(path: Parameters<typeof mswPath>[0]) {
    const keys: (string | null)[] = [];
    server.use(
      http.post(mswPath(path), ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        return HttpResponse.json({}, { status: 202 });
      }),
    );
    return keys;
  }

  it('adds a key to a POST that accepts one', async () => {
    const keys = captureKey('/api/v1/etl/dlq/{id}/replay');
    await api.POST('/api/v1/etl/dlq/{id}/replay', { params: { path: { id: 'd1' } } });
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('keeps the key that the caller passed, so a retry of the same action reuses it', async () => {
    const keys = captureKey('/api/v1/etl/jobs/{runId}/stop');
    const params = { path: { runId: 'r1' }, header: { 'Idempotency-Key': 'k-1' } };
    await api.POST('/api/v1/etl/jobs/{runId}/stop', { params });
    await api.POST('/api/v1/etl/jobs/{runId}/stop', { params });
    expect(keys).toEqual(['k-1', 'k-1']);
  });

  it('adds no key to a POST that is idempotent by itself', async () => {
    const keys = captureKey('/api/v1/alerts/{id}/ack');
    await api.POST('/api/v1/alerts/{id}/ack', { params: { path: { id: 'a1' } } });
    expect(keys).toEqual([null]);
  });
});
