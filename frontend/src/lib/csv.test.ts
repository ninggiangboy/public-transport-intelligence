import { describe, expect, it } from 'vitest';

import { toCsv } from '@/lib/csv';

describe('toCsv', () => {
  it('writes a header and rows with CRLF line ends', () => {
    expect(toCsv(['a', 'b'], [[1, 2.5]])).toBe('a,b\r\n1,2.5\r\n');
  });

  it('quotes fields with commas, quotes or line breaks and leaves missing values empty', () => {
    expect(toCsv(['name', 'n'], [['Lake St, Selby "Ave"', null]])).toBe('name,n\r\n"Lake St, Selby ""Ave""",\r\n');
  });
});
