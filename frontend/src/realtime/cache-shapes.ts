/**
 * The cache holds what a query function returned, and screens decide what that is. By the convention of
 * `src/api/client.ts` (`queryFn: () => read(api.GET(...))`) it is `{ data, asOf }`, but a plain REST body and the
 * `{ pages }` of `useInfiniteQuery` are just as plausible. The real-time handlers patch all of them, so that the shape
 * of an entry never changes under a screen (DOC-26 §8.4).
 */
import type { WithAsOf } from '@/api/client';

type Rec = Record<string, unknown>;

export function isRecord(value: unknown): value is Rec {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `{ data, asOf }` as returned by `read()`. A REST body never has a `data` key of its own. */
function isWrapped(cached: Rec): cached is Rec & { data: Rec } {
  return isRecord(cached.data) && !('items' in cached);
}

/** The REST body inside a cache entry, whatever its wrapper; `undefined` when the entry is not a single body. */
// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the caller names the REST body type
export function bodyOf<T>(cached: unknown): T | undefined {
  if (!isRecord(cached)) return undefined;
  return (isWrapped(cached) ? cached.data : cached) as T;
}

/** Applies `fn` to the REST body inside `cached`, keeping the wrapper. Returns `cached` itself when `fn` changed nothing. */
export function mapBody<T>(cached: unknown, fn: (body: T) => T): unknown {
  if (!isRecord(cached)) return cached;
  if (isWrapped(cached)) {
    const next = fn(cached.data as T);
    return next === (cached.data as unknown) ? cached : { ...cached, data: next };
  }
  return fn(cached as T);
}

/**
 * Applies `fn` to every page of a list entry: a single page (plain or wrapped) or the pages of an infinite query.
 * `index` is the page number, so that callers can insert new events on the first page only.
 */
export function mapPages<T>(cached: unknown, fn: (page: T, index: number) => T): unknown {
  if (isRecord(cached) && Array.isArray(cached.pages)) {
    const pages = cached.pages as unknown[];
    const next = pages.map((page, index) => mapBody<T>(page, (body) => fn(body, index)));
    return next.some((page, index) => page !== pages[index]) ? { ...cached, pages: next } : cached;
  }
  return mapBody<T>(cached, (body) => fn(body, 0));
}

/** Wraps a fresh body the way the existing entry is wrapped; a new entry gets the `read()` shape. */
export function shapeLike(existing: unknown, body: unknown, asOf: string | undefined): unknown {
  if (isRecord(existing) && !isWrapped(existing) && !Array.isArray(existing.pages)) return body;
  const wrapped: WithAsOf<unknown> = { data: body, asOf };
  return wrapped;
}
