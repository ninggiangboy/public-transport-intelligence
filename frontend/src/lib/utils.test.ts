import { describe, expect, it } from 'vitest';

import { cn } from '@/lib/utils';

describe('cn', () => {
  it('keeps a size of the type scale next to a text colour', () => {
    expect(cn('text-kpi font-semibold', 'text-tone-warning-fg')).toBe('text-kpi font-semibold text-tone-warning-fg');
    expect(cn('text-label', 'text-muted-foreground')).toBe('text-label text-muted-foreground');
  });

  it('lets a later size of the scale replace an earlier one', () => {
    expect(cn('text-sm', 'text-kpi')).toBe('text-kpi');
  });
});
