import { useQueryClient } from '@tanstack/react-query';
import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { toast } from 'sonner';

import { setAuth } from '@/api/client';
import { keys } from '@/api/keys';
import { safeReturnPath, SessionContext, SIGN_IN_UNAVAILABLE, type Session } from '@/app/session';
import type { AppEnv } from '@/env';
import { en } from '@/i18n/en';

/** The account skeleton shows at most this long while the start-up silent sign-in runs (DOC-34 §9.3). */
export const RESTORE_WAIT_MS = 1_500;

export const CALLBACK_PATH = '/auth/callback';

/** The client of DOC-34 §9.3, or `undefined` when `env.js` names no Keycloak. */
export function createUserManager(appEnv: AppEnv, origin = globalThis.location.origin): UserManager | undefined {
  if (!appEnv.keycloakUrl) return undefined;
  return new UserManager({
    authority: `${appEnv.keycloakUrl.replace(/\/+$/, '')}/realms/${appEnv.keycloakRealm}`,
    client_id: appEnv.keycloakClientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    silent_redirect_uri: `${origin}/auth/silent.html`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    // Tokens live in memory only (DOC-27 §3.2); a reload signs in again through signinSilent.
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    automaticSilentRenew: true,
    monitorSession: false,
  });
}

/** Item 2 of DOC-34 §9.2. Without Keycloak there is nothing to provide. */
export function OidcProvider({ userManager, children }: { userManager: UserManager | undefined; children: ReactNode }) {
  if (!userManager) return children;
  // /auth/callback completes the sign-in itself, so that it can show its own progress and error states.
  return (
    <AuthProvider userManager={userManager} skipSigninCallback>
      {children}
    </AuthProvider>
  );
}

/**
 * Turns the OIDC state into the app's {@link Session}. It sits inside `QueryClientProvider`, because signing out
 * clears the query cache and a new token refetches `/me`.
 */
export function SessionProvider({
  userManager,
  children,
}: {
  userManager: UserManager | undefined;
  children: ReactNode;
}) {
  if (!userManager) return <SessionContext value={SIGN_IN_UNAVAILABLE}>{children}</SessionContext>;
  return <OidcSession userManager={userManager}>{children}</OidcSession>;
}

// One start-up silent sign-in per client, even when StrictMode runs effects twice.
const restoreStarted = new WeakSet<UserManager>();
// The redirect callback consumes its state once; a second call (StrictMode) reuses the first result.
const callbacks = new WeakMap<UserManager, Promise<string>>();

function OidcSession({ userManager, children }: { userManager: UserManager; children: ReactNode }) {
  const auth = useAuth();
  const queryClient = useQueryClient();
  const [restoring, setRestoring] = useState(true);
  // Read synchronously by the API client, which may ask for the token right after a renewal, before React re-renders.
  const token = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (restoreStarted.has(userManager)) return;
    restoreStarted.add(userManager);
    const done = () => {
      setRestoring(false);
    };
    const timer = setTimeout(done, RESTORE_WAIT_MS);
    if (globalThis.location.pathname === CALLBACK_PATH) return;
    // An existing Keycloak session signs the user in without a visible redirect; otherwise they stay anonymous.
    userManager
      .signinSilent()
      .catch(() => undefined)
      .finally(() => {
        clearTimeout(timer);
        done();
      });
  }, [userManager]);

  useEffect(() => {
    const loaded = (user: User) => {
      token.current = user.access_token;
    };
    const unloaded = () => {
      token.current = undefined;
    };
    userManager.events.addUserLoaded(loaded);
    userManager.events.addUserUnloaded(unloaded);
    return () => {
      userManager.events.removeUserLoaded(loaded);
      userManager.events.removeUserUnloaded(unloaded);
    };
  }, [userManager]);

  const user = auth.user && !auth.user.expired ? auth.user : undefined;
  const accessToken = user?.access_token;
  useEffect(() => {
    token.current = accessToken;
  }, [accessToken]);

  const signIn = useCallback(
    async (returnTo: string = globalThis.location.href) => {
      try {
        await userManager.signinRedirect({ state: { returnTo } });
      } catch {
        toast.error(en.auth.unreachable);
      }
    },
    [userManager],
  );

  const signOut = useCallback(async () => {
    // Data fetched with the operator's rights must not outlive the session in memory (UX-10).
    queryClient.clear();
    try {
      await userManager.signoutRedirect();
    } catch {
      // Keycloak is unreachable: end the session here at least.
      await userManager.removeUser();
      globalThis.location.assign('/');
    }
  }, [queryClient, userManager]);

  const completeSignIn = useCallback(() => {
    let pending = callbacks.get(userManager);
    if (!pending) {
      pending = userManager.signinRedirectCallback().then((signedIn) => {
        setRestoring(false);
        const state = signedIn.state as { returnTo?: unknown } | undefined;
        return safeReturnPath(state?.returnTo);
      });
      callbacks.set(userManager, pending);
    }
    return pending;
  }, [userManager]);

  // The API client renews once on a 401 and gives up after that (DOC-34 §9.3, UX-06).
  useEffect(() => {
    setAuth({
      accessToken: () => token.current,
      renew: async () => {
        try {
          const renewed = await userManager.signinSilent();
          token.current = renewed?.access_token;
          return renewed !== null;
        } catch {
          return false;
        }
      },
      expired: () => {
        token.current = undefined;
        void userManager.removeUser();
        queryClient.removeQueries({ queryKey: keys.me() });
        toast(en.auth.expired, {
          id: 'session-expired',
          action: { label: en.account.signIn, onClick: () => void signIn() },
        });
      },
    });
    return () => {
      setAuth(undefined);
    };
  }, [queryClient, signIn, userManager]);

  // Who the user is follows the token: sign-in, renewal and sign-out refetch /me (DOC-34 §3).
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: keys.me() });
  }, [accessToken, queryClient]);

  const session = useMemo<Session>(
    () => ({
      status: user ? 'authenticated' : restoring || auth.isLoading ? 'restoring' : 'anonymous',
      available: true,
      accessToken,
      displayName: user?.profile.name ?? user?.profile.preferred_username,
      signIn,
      signOut,
      completeSignIn,
    }),
    [user, restoring, auth.isLoading, accessToken, signIn, signOut, completeSignIn],
  );
  return <SessionContext value={session}>{children}</SessionContext>;
}
