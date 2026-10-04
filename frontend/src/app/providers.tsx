import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { useState } from 'react';

import { oidcSettings, SessionProvider } from '@/app/auth';
import { FreshnessProvider, LiveBusinessClock, SessionStreamSync } from '@/app/freshness';
import { createQueryClient } from '@/app/query-client';
import { ThemeProvider } from '@/app/theme-provider';
import { env } from '@/env';
import { RealtimeProvider } from '@/realtime/RealtimeProvider';
import { createAppRouter } from '@/router';

// DOC-34 §9.2: Theme → Query → Auth → Freshness → Realtime → Router. Auth sits inside Query, because signing out clears
// the cache; the business clock sits inside Realtime, because heartbeats move it.
export function AppProviders() {
  const [queryClient] = useState(createQueryClient);
  const [settings] = useState(() => oidcSettings(env));
  const [router] = useState(() => createAppRouter({ queryClient }));
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <SessionProvider settings={settings}>
          <FreshnessProvider>
            <RealtimeProvider>
              <SessionStreamSync />
              <LiveBusinessClock>
                <RouterProvider router={router} />
              </LiveBusinessClock>
            </RealtimeProvider>
          </FreshnessProvider>
        </SessionProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
