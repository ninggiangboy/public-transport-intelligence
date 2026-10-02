// Search params as plain query strings (DOC-34 §5.1) instead of TanStack Router's JSON encoding, so that URLs stay
// readable and match DOC-04: lists are comma-separated, defaults are left out, and every value is a string that the
// route's zod schema coerces and validates.

export type SearchValue = string | number | boolean | readonly (string | number | boolean)[] | null | undefined;
export type SearchRecord = Record<string, unknown>;

/**
 * `?status=NEW,MANUAL&from=2026-09-29T20:00Z` → `{ status: ['NEW', 'MANUAL'], from: '2026-09-29T20:00Z' }`.
 * A list with one item comes back as a plain string, so list schemas accept both shapes.
 */
export function parseSearch(search: string): SearchRecord {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const result: SearchRecord = {};
  for (const [key, raw] of params) {
    if (raw === '') continue;
    result[key] = raw.includes(',') ? raw.split(',').filter((part) => part !== '') : raw;
  }
  return result;
}

/** Inverse of {@link parseSearch}. Empty values and empty lists are dropped; keys keep their insertion order. */
export function stringifySearch(search: SearchRecord): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(search)) {
    const encoded = encodeValue(value);
    if (encoded !== undefined) params.set(key, encoded);
  }
  // Commas separate list items and colons appear in timestamps; both are legal in a query string, so keep them readable.
  const query = params.toString().replaceAll('%2C', ',').replaceAll('%3A', ':');
  return query === '' ? '' : `?${query}`;
}

function encodeValue(value: unknown): string | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  if (Array.isArray(value)) {
    const items = value.filter((item) => item !== undefined && item !== null && item !== '').map(String);
    return items.length === 0 ? undefined : items.join(',');
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return undefined;
}

/**
 * Removes the keys whose value equals the route's default, so that defaults never appear on the URL (DOC-34 §5.1).
 * Meant for a route's `search.middlewares`.
 */
export function stripDefaults<T extends SearchRecord>(search: T, defaults: Partial<T>): Partial<T> {
  const result: Partial<T> = {};
  for (const key of Object.keys(search) as (keyof T)[]) {
    if (!sameValue(search[key], defaults[key])) result[key] = search[key];
  }
  return result;
}

function sameValue(a: unknown, b: unknown): boolean {
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((item, index) => item === b[index]);
  }
  return a === b;
}
