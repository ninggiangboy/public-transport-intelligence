import { useQueryClient } from '@tanstack/react-query';
import type { User, UserManager, UserManagerSettings } from 'oidc-client-ts';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import { setAuth } from '@/api/client';
import { keys } from '@/api/keys';
import { safeReturnPath, SessionContext, SIGN_IN_UNAVAILABLE, type Session } from '@/app/session';
import type { AppEnv } from '@/env';
import { en } from '@/i18n/en';
import { notify } from '@/lib/notify';

/** The account skeleton shows at most this long while the start-up silent sign-in runs (DOC-34 §9.3). */
export const RESTORE_WAIT_MS = 1_500;

export const CALLBACK_PATH = '/auth/callback';

/** Settings of the client of DOC-34 §9.3 (the user store is added on load), or `undefined` without Keycloak. */
export type OidcSettings = Omit<UserManagerSettings, 'userStore'>;

export function oidcSettings(appEnv: AppEnv, origin = globalThis.location.origin): OidcSettings | undefined {
  if (!appEnv.keycloakUrl) return undefined;
  return {
    authority: `${appEnv.keycloakUrl.replace(/\/+$/, '')}/realms/${appEnv.keycloakRealm}`,
    client_id: appEnv.keycloakClientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    silent_redirect_uri: `${origin}/auth/silent.html`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    automaticSilentRenew: true,
    monitorSession: false,
  };
}

/**
 * Loads oidc-client-ts on demand, so that it stays out of the initial bundle (DOC-34 §7): public pages render before
 * it arrives, and the start-up silent sign-in runs once it has.
 */
export async function loadUserManager(settings: OidcSettings): Promise<UserManager> {
  const oidc = await import('oidc-client-ts');
  return new oidc.UserManager({
    ...settings,
    // Tokens live in memory only (DOC-27 §3.2); a reload signs in again through signinSilent.
    userStore: new oidc.WebStorageStateStore({ store: new oidc.InMemoryWebStorage() }),
  });
}

interface SessionProviderProps {
  settings: OidcSettings | undefined;
  /** Tests pass a manager of their own. */
  load?: (settings: OidcSettings) => Promise<UserManager>;
  children: ReactNode;
}

/**
 * Item 2 of DOC-34 §9.2, turned into the app's {@link Session}. It sits inside `QueryClientProvider`, because signing
 * out clears the query cache and a new token refetches `/me`. Without Keycloak everyone is anonymous.
 */
export function SessionProvider({ settings, load = loadUserManager, children }: SessionProviderProps) {
  if (!settings) return <SessionContext value={SIGN_IN_UNAVAILABLE}>{children}</SessionContext>;
  return (
    <OidcSession settings={settings} load={load}>
      {children}
    </OidcSession>
  );
}

// One start-up silent sign-in per client, even when StrictMode runs effects twice.
const restoreStarted = new WeakSet<UserManager>();
// The redirect callback consumes its state once; a second call (StrictMode) reuses the first result.
const callbacks = new WeakMap<UserManager, Promise<string>>();

interface OidcSessionProps {
  settings: OidcSettings;
  load: (settings: OidcSettings) => Promise<UserManager>;
  children: ReactNode;
}

function OidcSession({ settings, load, children }: OidcSessionProps) {
  const queryClient = useQueryClient();
  const [manager] = useState(() => load(settings));
  const [user, setUser] = useState<User | null>(null);
  const [restoring, setRestoring] = useState(true);
  // Read synchronously by the API client, which may ask for the token right after a renewal, before React re-renders.
  const token = useRef<string | undefined>(undefined);

  // The skeleton waits 1.5 s at most, whether or not the client has loaded by then.
  useEffect(() => {
    const timer = setTimeout(() => {
      setRestoring(false);
    }, RESTORE_WAIT_MS);
    return () => {
      clearTimeout(timer);
    };
  }, []);

  useEffect(() => {
    // An object, so that the async callback reads the cleanup's change.
    const effect = { active: true, unsubscribe: () => undefined as unknown };
    const active = () => effect.active;
    void manager.then(async (userManager) => {
      if (!active()) return;
      const loaded = (next: User) => {
        token.current = next.access_token;
        setUser(next);
      };
      const unloaded = () => {
        token.current = undefined;
        setUser(null);
      };
      userManager.events.addUserLoaded(loaded);
      userManager.events.addUserUnloaded(unloaded);
      effect.unsubscribe = () => {
        userManager.events.removeUserLoaded(loaded);
        userManager.events.removeUserUnloaded(unloaded);
      };
      const stored = await userManager.getUser();
      if (active() && stored && !stored.expired) loaded(stored);

      if (restoreStarted.has(userManager) || globalThis.location.pathname === CALLBACK_PATH) return;
      restoreStarted.add(userManager);
      // An existing Keycloak session signs the user in without a visible redirect; otherwise they stay anonymous.
      await userManager.signinSilent().catch(() => undefined);
      if (active()) setRestoring(false);
    });
    return () => {
      effect.active = false;
      effect.unsubscribe();
    };
  }, [manager]);

  const signIn = useCallback(
    async (returnTo: string = globalThis.location.href) => {
      try {
        await (await manager).signinRedirect({ state: { returnTo } });
      } catch {
        notify.error(en.auth.unreachable);
      }
    },
    [manager],
  );

  const signOut = useCallback(async () => {
    // Data fetched with the operator's rights must not outlive the session in memory (UX-10).
    queryClient.clear();
    const userManager = await manager;
    try {
      await userManager.signoutRedirect();
    } catch {
      // Keycloak is unreachable: end the session here at least.
      await userManager.removeUser();
      globalThis.location.assign('/');
    }
  }, [manager, queryClient]);

  const completeSignIn = useCallback(async () => {
    const userManager = await manager;
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
  }, [manager]);

  // The API client renews once on a 401 and gives up after that (DOC-34 §9.3, UX-06).
  useEffect(() => {
    setAuth({
      accessToken: () => token.current,
      renew: async () => {
        try {
          const renewed = await (await manager).signinSilent();
          token.current = renewed?.access_token;
          return renewed !== null;
        } catch {
          return false;
        }
      },
      expired: () => {
        token.current = undefined;
        void manager.then((userManager) => userManager.removeUser());
        queryClient.removeQueries({ queryKey: keys.me() });
        notify.message(en.auth.expired, {
          id: 'session-expired',
          action: { label: en.account.signIn, onClick: () => void signIn() },
        });
      },
    });
    return () => {
      setAuth(undefined);
    };
  }, [manager, queryClient, signIn]);

  const current = user && !user.expired ? user : undefined;
  const accessToken = current?.access_token;

  // Who the user is follows the token: sign-in, renewal and sign-out refetch /me (DOC-34 §3).
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: keys.me() });
  }, [accessToken, queryClient]);

  const session = useMemo<Session>(
    () => ({
      status: current ? 'authenticated' : restoring ? 'restoring' : 'anonymous',
      available: true,
      accessToken,
      displayName: current?.profile.name ?? current?.profile.preferred_username,
      signIn,
      signOut,
      completeSignIn,
    }),
    [current, restoring, accessToken, signIn, signOut, completeSignIn],
  );
  return <SessionContext value={session}>{children}</SessionContext>;
}
