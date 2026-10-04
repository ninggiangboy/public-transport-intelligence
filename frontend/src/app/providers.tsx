import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { useState } from 'react';

import { createUserManager, OidcProvider, SessionProvider } from '@/app/auth';
import { FreshnessProvider, LiveBusinessClock, SessionStreamSync } from '@/app/freshness';
import { createQueryClient } from '@/app/query-client';
import { ThemeProvider } from '@/app/theme-provider';
import { env } from '@/env';
import { RealtimeProvider } from '@/realtime/RealtimeProvider';
import { createAppRouter } from '@/router';

// DOC-34 §9.2: Theme → Auth → Query → Freshness → Realtime → Router. The session half of Auth sits inside Query, because
// signing out clears the cache; the business clock sits inside Realtime, because heartbeats move it.
export function AppProviders() {
  const [queryClient] = useState(createQueryClient);
  const [userManager] = useState(() => createUserManager(env));
  const [router] = useState(() => createAppRouter({ queryClient }));
  return (
    <ThemeProvider>
      <OidcProvider userManager={userManager}>
        <QueryClientProvider client={queryClient}>
          <SessionProvider userManager={userManager}>
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
      </OidcProvider>
    </ThemeProvider>
  );
}
