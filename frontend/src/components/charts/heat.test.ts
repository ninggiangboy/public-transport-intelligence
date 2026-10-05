import { describe, expect, it } from 'vitest';

import { heatPieces } from '@/components/charts/heat';

describe('heatPieces', () => {
  it('maps early delay to level 1 and seven minutes late or more to level 7', () => {
    const pieces = heatPieces('delay');
    expect(pieces[0]).toEqual({ level: 1, lt: 0 });
    expect(pieces[4]).toEqual({ level: 5, gte: 180, lt: 300 });
    expect(pieces[6]).toEqual({ level: 7, gte: 420 });
  });

  it('runs the other way for on-time percentage', () => {
    const pieces = heatPieces('otp');
    expect(pieces[0]).toEqual({ level: 1, gte: 95 });
    expect(pieces[6]).toEqual({ level: 7, lt: 60 });
  });
});
