import { createContext, useContext } from 'react';

/**
 * `restoring` while the silent sign-in of start-up runs (at most 1.5 s, DOC-34 §9.3), then `authenticated` with a
 * token or `anonymous`. Roles are not here: they come from `GET /me` (see access.ts).
 */
export type SessionStatus = 'restoring' | 'anonymous' | 'authenticated';

export interface Session {
  status: SessionStatus;
  /** `false` when `env.js` names no Keycloak: everyone is anonymous and "Sign in" is hidden. */
  available: boolean;
  /** Current access token; it changes on sign-in, renewal and sign-out. */
  accessToken?: string;
  /** Name from the ID token, shown while `/me` loads. */
  displayName?: string;
  /** Redirects to Keycloak; the callback returns to `returnTo` (default: the current URL). */
  signIn: (returnTo?: string) => Promise<void>;
  /** Clears the query cache, then signs out at Keycloak (DOC-34 §9.3, UX-10). */
  signOut: () => Promise<void>;
  /** Completes the redirect sign-in on /auth/callback and resolves to the same-origin path to return to. */
  completeSignIn: () => Promise<string>;
}

const noop = () => Promise.resolve();

/** Without Keycloak (and outside any provider): anonymous for good. */
export const SIGN_IN_UNAVAILABLE: Session = {
  status: 'anonymous',
  available: false,
  signIn: noop,
  signOut: noop,
  completeSignIn: () => Promise.resolve('/'),
};

export const SessionContext = createContext<Session>(SIGN_IN_UNAVAILABLE);

export function useSession(): Session {
  return useContext(SessionContext);
}

/**
 * `returnTo` as a path of this origin, or `/`. The value travels through Keycloak in the OIDC state, so only a
 * same-origin URL is followed (DOC-34 §9.3).
 */
export function safeReturnPath(returnTo: unknown, origin = globalThis.location.origin): string {
  if (typeof returnTo !== 'string' || returnTo === '') return '/';
  try {
    const url = new URL(returnTo, origin);
    if (url.origin !== origin || url.pathname.startsWith('/auth/')) return '/';
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return '/';
  }
}
