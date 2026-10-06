import { describe, expect, it } from 'vitest';

import { lineOfPointer } from '@/lib/json-lines';

describe('lineOfPointer', () => {
  const text = JSON.stringify(
    { vehicle: { id: '1' }, position: { latitude: 'abc', longitude: 2 }, list: [1, { x: 3 }] },
    null,
    2,
  );

  it('finds the line of a field, an object and an array element in the pretty text', () => {
    expect(lineOfPointer(text, '/position/latitude')).toBe(6);
    expect(lineOfPointer(text, '/position')).toBe(5);
    expect(lineOfPointer(text, '/list/1/x')).toBe(12);
  });

  it('falls back to the deepest parent found, and to the first line', () => {
    expect(lineOfPointer(text, '/position/altitude')).toBe(5);
    expect(lineOfPointer(text, '/missing')).toBe(1);
    expect(lineOfPointer(text, '')).toBe(1);
  });

  it('reads the escapes of a pointer', () => {
    expect(lineOfPointer('{\n  "a/b": {\n    "c~d": 1\n  }\n}', '/a~1b/c~0d')).toBe(3);
  });
});
