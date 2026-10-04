import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { User, type UserManager } from 'oidc-client-ts';
import { useEffect } from 'react';
import type * as Sonner from 'sonner';
import { toast } from 'sonner';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getAuth } from '@/api/client';
import { createUserManager, OidcProvider, SessionProvider } from '@/app/auth';
import { safeReturnPath, useSession, type Session } from '@/app/session';
import { SAFE_DEFAULTS } from '@/env';
import { en } from '@/i18n/en';

vi.mock('sonner', async (importOriginal) => {
  const actual = await importOriginal<typeof Sonner>();
  return { ...actual, toast: Object.assign(vi.fn(), { error: vi.fn() }) };
});

const ORIGIN = globalThis.location.origin;
const ENV = { ...SAFE_DEFAULTS, keycloakUrl: 'http://keycloak.test/' };

function signedInUser(state?: unknown): User {
  const expiresAt = Math.floor(Date.now() / 1000) + 300;
  return new User({
    access_token: 'access-1',
    token_type: 'Bearer',
    expires_at: expiresAt,
    profile: {
      sub: 'u1',
      iss: 'http://keycloak.test/realms/pti',
      aud: 'pti-web',
      exp: expiresAt,
      iat: 0,
      name: 'Linh Tran',
    },
    userState: state,
  });
}

let current: Session | undefined;
function Probe() {
  const session = useSession();
  useEffect(() => {
    current = session;
  });
  return <span data-testid="status">{session.status}</span>;
}

function setup(silent: (userManager: UserManager) => Promise<User | null>) {
  const userManager = createUserManager(ENV);
  if (!userManager) throw new Error('no user manager');
  const spies = {
    signinSilent: vi.spyOn(userManager, 'signinSilent').mockImplementation(() => silent(userManager)),
    signinRedirect: vi.spyOn(userManager, 'signinRedirect').mockResolvedValue(undefined),
    signoutRedirect: vi.spyOn(userManager, 'signoutRedirect').mockResolvedValue(undefined),
    removeUser: vi.spyOn(userManager, 'removeUser'),
  };
  const queryClient = new QueryClient();
  render(
    <OidcProvider userManager={userManager}>
      <QueryClientProvider client={queryClient}>
        <SessionProvider userManager={userManager}>
          <Probe />
        </SessionProvider>
      </QueryClientProvider>
    </OidcProvider>,
  );
  return { userManager, queryClient, spies };
}

/** What signinSilent does on success: store the user and raise `userLoaded`. */
async function succeed(userManager: UserManager) {
  const user = signedInUser();
  await userManager.storeUser(user);
  await userManager.events.load(user);
  return user;
}

function session(): Session {
  if (!current) throw new Error('no session rendered');
  return current;
}

beforeEach(() => {
  current = undefined;
});
afterEach(() => {
  vi.useRealTimers();
});

describe('createUserManager (DOC-34 §9.3)', () => {
  it('configures the pti-web client with PKCE and in-memory tokens', () => {
    const settings = createUserManager(ENV)?.settings;
    expect(settings?.authority).toBe('http://keycloak.test/realms/pti');
    expect(settings?.client_id).toBe('pti-web');
    expect(settings?.redirect_uri).toBe(`${ORIGIN}/auth/callback`);
    expect(settings?.silent_redirect_uri).toBe(`${ORIGIN}/auth/silent.html`);
    expect(settings?.post_logout_redirect_uri).toBe(`${ORIGIN}/`);
    expect(settings?.response_type).toBe('code');
    expect(settings?.scope).toBe('openid profile');
    expect(settings?.automaticSilentRenew).toBe(true);
    expect(settings?.monitorSession).toBe(false);
  });

  it('is absent without a Keycloak URL', () => {
    expect(createUserManager(SAFE_DEFAULTS)).toBeUndefined();
  });
});

describe('OIDC session', () => {
  it('restores a Keycloak session silently at start-up (AC-5)', async () => {
    const { spies } = setup(succeed);
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    expect(spies.signinSilent).toHaveBeenCalledTimes(1);
    expect(session().displayName).toBe('Linh Tran');
    expect(session().accessToken).toBe('access-1');
    expect(getAuth()?.accessToken()).toBe('access-1');
  });

  it('stays anonymous when there is no Keycloak session', async () => {
    setup(() => Promise.reject(new Error('login_required')));
    expect(screen.getByTestId('status')).toHaveTextContent('restoring');
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
    });
    expect(getAuth()?.accessToken()).toBeUndefined();
  });

  it('gives up waiting after 1.5 s when Keycloak does not answer', async () => {
    vi.useFakeTimers();
    setup(() => new Promise(() => undefined));
    expect(screen.getByTestId('status')).toHaveTextContent('restoring');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
  });

  it('signs in with the current URL as returnTo', async () => {
    const { spies } = setup(() => Promise.reject(new Error('login_required')));
    await act(() => session().signIn(`${ORIGIN}/ops/dlq?severity=2`));
    expect(spies.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: `${ORIGIN}/ops/dlq?severity=2` } });
  });

  it('says so when the sign-in service cannot be reached', async () => {
    const { spies } = setup(() => Promise.reject(new Error('login_required')));
    spies.signinRedirect.mockRejectedValue(new TypeError('Failed to fetch'));
    await act(() => session().signIn());
    expect(toast.error).toHaveBeenCalledWith(en.auth.unreachable);
  });

  it('UX-10 empties the query cache before signing out at Keycloak', async () => {
    const { queryClient, spies } = setup(() => Promise.reject(new Error('login_required')));
    queryClient.setQueryData(['etl', 'dlq', 'summary'], { open: 214 });
    queryClient.setQueryData(['me'], { authenticated: true, roles: ['operator'] });
    await act(() => session().signOut());
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
    expect(spies.signoutRedirect).toHaveBeenCalledTimes(1);
  });

  it('completes the redirect callback once and returns a same-origin path', async () => {
    const { userManager } = setup(() => Promise.reject(new Error('login_required')));
    const callback = vi
      .spyOn(userManager, 'signinRedirectCallback')
      .mockResolvedValue(signedInUser({ returnTo: `${ORIGIN}/ops/dlq?severity=2` }));
    const [first, second] = await Promise.all([session().completeSignIn(), session().completeSignIn()]);
    expect(first).toBe('/ops/dlq?severity=2');
    expect(second).toBe('/ops/dlq?severity=2');
    expect(callback).toHaveBeenCalledTimes(1);
  });

  it('ends the session and tells the user when a renewed token is refused too', async () => {
    const { spies, queryClient } = setup(succeed);
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    queryClient.setQueryData(['me'], { authenticated: true, roles: ['viewer'] });

    act(() => {
      getAuth()?.expired();
    });
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
    });
    expect(spies.removeUser).toHaveBeenCalled();
    expect(queryClient.getQueryData(['me'])).toBeUndefined();
    expect(toast).toHaveBeenCalledWith(en.auth.expired, expect.objectContaining({ id: 'session-expired' }));
  });
});

describe('safeReturnPath', () => {
  it.each([
    [`${ORIGIN}/ops/dlq?severity=2#x`, '/ops/dlq?severity=2#x'],
    ['/map?route=18', '/map?route=18'],
    ['https://evil.example/ops', '/'],
    ['//evil.example/ops', '/'],
    [`${ORIGIN}/auth/callback?code=1`, '/'],
    [undefined, '/'],
    [42, '/'],
  ])('%s → %s', (input, expected) => {
    expect(safeReturnPath(input)).toBe(expected);
  });
});
