import { describe, expect, it } from 'vitest';

import { parseSearch, stringifySearch, stripDefaults } from '@/lib/url';

describe('parseSearch and stringifySearch (UX-01)', () => {
  it('keeps lists and timestamps readable', () => {
    const query = stringifySearch({ status: ['NEW', 'MANUAL'], from: '2026-09-29T20:00Z', severity: [0, 2] });
    expect(query).toBe('?status=NEW,MANUAL&from=2026-09-29T20:00Z&severity=0,2');
  });

  it('round-trips lists, timestamps and scalars', () => {
    const search = { status: ['NEW', 'MANUAL'], from: '2026-09-29T20:00Z', tab: 'review' };
    expect(parseSearch(stringifySearch(search))).toEqual(search);
  });

  it('drops empty values and empty lists', () => {
    expect(stringifySearch({ q: '', route: [], vehicle: undefined, id: null })).toBe('');
  });

  it('returns a single list item as a string', () => {
    expect(parseSearch('?status=NEW')).toEqual({ status: 'NEW' });
  });

  it('ignores empty parameters and stray commas', () => {
    expect(parseSearch('?q=&status=NEW,,MANUAL,')).toEqual({ status: ['NEW', 'MANUAL'] });
  });

  it('encodes characters that would break the query', () => {
    const query = stringifySearch({ q: 'Nicollet & 46th' });
    expect(query).toBe('?q=Nicollet+%26+46th');
    expect(parseSearch(query)).toEqual({ q: 'Nicollet & 46th' });
  });
});

describe('stripDefaults', () => {
  it('leaves default values off the URL', () => {
    const defaults = { tab: 'review', window: '24h', status: ['NEW'] };
    const search = { tab: 'review', window: '7d', status: ['NEW'] };
    expect(stripDefaults(search, defaults)).toEqual({ window: '7d' });
  });

  it('keeps a list that differs from the default', () => {
    expect(stripDefaults({ status: ['NEW', 'MANUAL'] }, { status: ['NEW'] })).toEqual({ status: ['NEW', 'MANUAL'] });
  });
});
