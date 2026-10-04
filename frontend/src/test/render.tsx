import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { render } from '@testing-library/react';
import { vi } from 'vitest';

import { EnvProvider } from '@/app/env-context';
import { FreshnessProvider, LiveBusinessClock } from '@/app/freshness';
import { createQueryClient } from '@/app/query-client';
import { SessionContext, type Session } from '@/app/session';
import { ThemeProvider } from '@/app/theme-provider';
import { SAFE_DEFAULTS, type AppEnv } from '@/env';
import { createAppRouter } from '@/router';
import { respond } from '@/test/handlers';
import { server } from '@/test/server';

/** Who is looking: anonymous, a signed-in role, or a session still being restored. */
export type TestUser = 'anonymous' | 'viewer' | 'operator' | 'restoring';

export interface RenderRouteOptions {
  as?: TestUser;
  /** Overrides of env.js; Keycloak is configured by default so that "Sign in" shows. */
  env?: Partial<AppEnv>;
  session?: Partial<Session>;
}

const TEST_ENV: AppEnv = { ...SAFE_DEFAULTS, keycloakUrl: 'http://keycloak.test' };

/** A session double: signed in with a token for viewer and operator, with spies for the actions. */
export function fakeSession(as: TestUser, overrides: Partial<Session> = {}): Session {
  const signedIn = as === 'viewer' || as === 'operator';
  return {
    status: signedIn ? 'authenticated' : as === 'restoring' ? 'restoring' : 'anonymous',
    available: true,
    accessToken: signedIn ? `token-${as}` : undefined,
    displayName: signedIn ? 'Linh Tran' : undefined,
    signIn: vi.fn(() => Promise.resolve()),
    signOut: vi.fn(() => Promise.resolve()),
    completeSignIn: vi.fn(() => Promise.resolve('/')),
    ...overrides,
  };
}

/** `/me` (E-61) as the API answers it for that user. */
export function meHandler(as: 'viewer' | 'operator') {
  return respond('get', '/api/v1/me', {
    authenticated: true,
    username: as,
    displayName: 'Linh Tran',
    roles: as === 'operator' ? ['operator', 'viewer'] : ['viewer'],
  });
}

/**
 * Renders the whole app at `path` with an in-memory history; screens are tested through their routes (DOC-44 §10).
 * The shell is included, as are the providers it needs, except the event stream: outside a `RealtimeProvider`
 * `useRealtime` does nothing.
 */
export async function renderRoute(path: string, options: RenderRouteOptions = {}) {
  const as = options.as ?? 'anonymous';
  if (as === 'viewer' || as === 'operator') server.use(meHandler(as));
  const session = fakeSession(as, options.session);
  const appEnv = { ...TEST_ENV, ...options.env };

  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } });
  const router = createAppRouter({ queryClient }, createMemoryHistory({ initialEntries: [path] }));
  await router.load();
  const result = render(
    <ThemeProvider>
      <EnvProvider value={appEnv}>
        <QueryClientProvider client={queryClient}>
          <SessionContext value={session}>
            <FreshnessProvider>
              <LiveBusinessClock>
                <RouterProvider router={router} />
              </LiveBusinessClock>
            </FreshnessProvider>
          </SessionContext>
        </QueryClientProvider>
      </EnvProvider>
    </ThemeProvider>,
  );
  return { ...result, router, queryClient, session };
}
