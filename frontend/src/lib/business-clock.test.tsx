import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import { BusinessClockProvider, useBusinessClock } from '@/lib/business-clock';
import { browserTimeZone } from '@/lib/time';

describe('BusinessClock', () => {
  it('defaults to the wall clock and the browser time zone', () => {
    const { result } = renderHook(() => useBusinessClock());
    expect(Math.abs(result.current.now() - Date.now())).toBeLessThan(1000);
    expect(result.current.timezone).toBe(browserTimeZone());
  });

  it('takes businessNow and the agency zone from the provider', () => {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <BusinessClockProvider now={() => 1_000} timezone="America/Chicago">
        {children}
      </BusinessClockProvider>
    );
    const { result } = renderHook(() => useBusinessClock(), { wrapper });
    expect(result.current.now()).toBe(1_000);
    expect(result.current.timezone).toBe('America/Chicago');
  });
});
