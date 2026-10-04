import type { Session } from '@/app/session';

/**
 * `pnpm dev:mock` has no Keycloak: the app runs signed in, and MSW answers /me with the example of openapi.json (an
 * operator), so the staff screens can be viewed too.
 */
export const MOCK_SESSION: Session = {
  status: 'authenticated',
  available: true,
  accessToken: 'mock-token',
  displayName: 'Demo Operator',
  signIn: () => Promise.resolve(),
  signOut: () => Promise.resolve(),
  completeSignIn: () => Promise.resolve('/'),
};
