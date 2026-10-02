import { describe, expect, it } from 'vitest';

import { createQueryClient } from '@/app/query-client';

const httpError = (status: number) => Object.assign(new Error(`HTTP ${status}`), { status });

describe('createQueryClient retry policy (DOC-34 §9.2)', () => {
  const retry = createQueryClient().getDefaultOptions().queries?.retry;
  if (typeof retry !== 'function') throw new Error('retry must be a function');

  it.each([
    ['a network error', new TypeError('Failed to fetch')],
    ['a 5xx', httpError(503)],
  ])('retries %s at most twice', (_label, error) => {
    expect(retry(0, error)).toBe(true);
    expect(retry(1, error)).toBe(true);
    expect(retry(2, error)).toBe(false);
  });

  it('never retries a 4xx', () => {
    expect(retry(0, httpError(404))).toBe(false);
    expect(retry(0, httpError(429))).toBe(false);
  });
});
