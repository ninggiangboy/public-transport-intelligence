import { http, HttpResponse, type JsonBodyType } from 'msw';

import { allExamples } from '@/api/generated/examples';
import type { paths } from '@/api/generated/schema';
import type { ApiPath, HttpMethod, ResponseBody } from '@/api/types';

/** `/api/v1/stops/{stopId}` → `* /api/v1/stops/:stopId` (any origin), the MSW form of an OpenAPI path. */
export function mswPath(path: string): string {
  return `*${path.replaceAll(/\{([^}]+)\}/g, ':$1')}`;
}

/**
 * One handler per operation that has an example in openapi.json (DOC-32), answering with its first example. Shared
 * by the component tests and `pnpm dev:mock` (DOC-44 §10). Paths without parameters come first, so that
 * `/etl/jobs/summary` wins over `/etl/jobs/{runId}`.
 */
export const handlers = [...allExamples]
  .sort((a, b) => parameterCount(a.path) - parameterCount(b.path))
  .map((operation) => {
    const [body] = Object.values(operation.examples) as JsonBodyType[];
    return http[operation.method](mswPath(operation.path), () => HttpResponse.json(body, { status: operation.status }));
  });

function parameterCount(path: string): number {
  return path.split('{').length - 1;
}

/**
 * A typed one-off handler for a test: `server.use(respond('get', '/api/v1/routes', { feedVersionId: 3, items: [] }))`.
 * The body must match the 200 response of the operation.
 */
export function respond<P extends ApiPath, M extends keyof paths[P] & HttpMethod>(
  method: M,
  path: P,
  body: ResponseBody<P, M, 200>,
  init?: { headers?: Record<string, string> },
) {
  return http[method](mswPath(path), () => HttpResponse.json(body as JsonBodyType, { status: 200, ...init }));
}

/** A Problem Details response (DOC-30 §3) for `method path`: `respondProblem('get', '/api/v1/stops/{stopId}', 404, 'not-found')`. */
export function respondProblem(method: HttpMethod, path: ApiPath, status: number, slug: string) {
  return http[method](mswPath(path), () =>
    HttpResponse.json(
      {
        type: `urn:pti:problem:${slug}`,
        title: slug,
        status,
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
      },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  );
}
