import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';

import { oidcSettings, SessionProvider } from '@/app/auth';
import { FreshnessProvider, LiveBusinessClock, SessionStreamSync } from '@/app/freshness';
import { createQueryClient } from '@/app/query-client';
import { SessionContext } from '@/app/session';
import { ThemeProvider } from '@/app/theme-provider';
import { env } from '@/env';
import { MOCK_SESSION } from '@/mocks/session';
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
        <AppSession settings={settings}>
          <FreshnessProvider>
            <RealtimeProvider>
              <SessionStreamSync />
              <LiveBusinessClock>
                <RouterProvider router={router} />
              </LiveBusinessClock>
            </RealtimeProvider>
          </FreshnessProvider>
        </AppSession>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

/** OIDC in every build but `pnpm dev:mock`, where a fixed session stands in (src/mocks/session.ts). */
function AppSession({ settings, children }: { settings: ReturnType<typeof oidcSettings>; children: ReactNode }) {
  // Vite replaces MODE at build time, so other builds drop this branch.
  if (import.meta.env.MODE === 'mock') return <SessionContext value={MOCK_SESSION}>{children}</SessionContext>;
  return <SessionProvider settings={settings}>{children}</SessionProvider>;
}
