import createClient, { type Middleware } from 'openapi-fetch';

import type { paths } from '@/api/generated/schema';
import { ApiError } from '@/api/problem';

/**
 * How the client gets and renews the access token. The OIDC provider (P5-04) installs it with {@link setAuth};
 * until then, and for anonymous users, requests go without a token.
 */
export interface AuthAdapter {
  /** The current access token, or `undefined` when anonymous. */
  accessToken(): string | undefined;
  /** Renews the session silently (`signinSilent`, DOC-34 §9.3); resolves to whether a new token is available. */
  renew(): Promise<boolean>;
  /** Called when a renewed token is rejected too: the user becomes anonymous and sees "Your session has expired". */
  expired(): void;
}

let auth: AuthAdapter | undefined;

export function setAuth(adapter: AuthAdapter | undefined) {
  auth = adapter;
}

/** The installed adapter, for the event stream, which sends the token itself (DOC-26 §8.2). */
export function getAuth(): AuthAdapter | undefined {
  return auth;
}

/** POSTs that accept `Idempotency-Key` (DOC-31 §8). */
const IDEMPOTENT_POSTS = [
  /^\/api\/v1\/etl\/replays$/,
  /^\/api\/v1\/etl\/dlq\/[^/]+\/(replay|confirm)$/,
  /^\/api\/v1\/etl\/jobs$/,
  /^\/api\/v1\/etl\/jobs\/[^/]+\/(restart|stop)$/,
];

/** A fresh key for one user action. Pass it in the request headers and reuse it when the same action is retried. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

// Requests that carried a token, as they were before fetch consumed the body: kept for one retry after a renewal.
// The retry goes to fetch directly, past the middleware, so it cannot trigger another renewal.
const pristine = new WeakMap<Request, Request>();

const authMiddleware: Middleware = {
  onRequest({ request }) {
    const token = auth?.accessToken();
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`);
      pristine.set(request, request.clone());
    }
    return request;
  },
  // UX-06: a 401 on a request that carried a token renews the session once and sends the request again.
  async onResponse({ request, response }) {
    const original = pristine.get(request);
    if (response.status !== 401 || !auth || !original) return response;
    if (!(await auth.renew())) {
      auth.expired();
      return response;
    }
    const retry = new Request(original);
    retry.headers.set('Authorization', `Bearer ${auth.accessToken() ?? ''}`);
    const second = await fetch(retry);
    if (second.status === 401) auth.expired();
    return second;
  },
};

const idempotencyMiddleware: Middleware = {
  onRequest({ request }) {
    const path = new URL(request.url).pathname;
    if (
      request.method === 'POST' &&
      !request.headers.has('Idempotency-Key') &&
      IDEMPOTENT_POSTS.some((pattern) => pattern.test(path))
    ) {
      request.headers.set('Idempotency-Key', newIdempotencyKey());
    }
    return request;
  },
};

function origin(): string {
  // Paths in openapi.json carry /api/v1 already. Absolute URLs keep Request happy under Node (tests) as well.
  return globalThis.location.origin;
}

export const api = createClient<paths>({
  baseUrl: origin(),
  // Looked up on every call instead of once at import, so that a fetch installed later (MSW in tests) is used.
  fetch: (request) => globalThis.fetch(request),
});
api.use(idempotencyMiddleware, authMiddleware);

/** Result of a read: the body plus `X-Data-As-Of` for the FreshnessIndicator (P-1, DOC-31 §7.3). */
export interface WithAsOf<T> {
  data: T;
  asOf: string | undefined;
}

/**
 * Unwraps an openapi-fetch result for TanStack Query: returns the body with its `X-Data-As-Of`, or throws
 * {@link ApiError}. Usage: `queryFn: () => read(api.GET('/api/v1/routes'))`.
 */
export async function read<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<WithAsOf<T>> {
  const { data, error, response } = await call;
  if (!response.ok) throw ApiError.from(response, error);
  return { data: data as T, asOf: response.headers.get('X-Data-As-Of') ?? undefined };
}

/** Like {@link read} for writes, which have no `X-Data-As-Of`. */
export async function write<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  return (await read(call)).data;
}
