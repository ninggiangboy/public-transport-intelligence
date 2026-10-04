import { useQuery } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import type { components } from '@/api/generated/schema';
import { keys } from '@/api/keys';
import { useSession } from '@/app/session';

export type CurrentUser = components['schemas']['CurrentUserResponse'];

/** Roles of DOC-34 §3 above anonymous, lowest first. */
export type Role = 'viewer' | 'operator';

export interface Access {
  /** The session is being restored or `/me` has not answered yet: nothing role-dependent is known. */
  pending: boolean;
  signedIn: boolean;
  /** Highest role; `undefined` for anonymous and for a signed-in user without one. */
  role?: Role;
  me?: CurrentUser;
  /** `/me` failed. */
  error?: unknown;
  refetch: () => void;
}

export function roleOf(roles: readonly string[]): Role | undefined {
  if (roles.includes('operator')) return 'operator';
  if (roles.includes('viewer')) return 'viewer';
  return undefined;
}

export function hasRole(access: Pick<Access, 'role'>, required: Role): boolean {
  return access.role === 'operator' || access.role === required;
}

/** Who the user is and what they may see, from `GET /me` (E-61), never from token claims (DOC-34 §3). */
export function useAccess(): Access {
  const session = useSession();
  const authenticated = session.status === 'authenticated';
  const me = useQuery({
    queryKey: keys.me(),
    queryFn: async () => (await read(api.GET('/api/v1/me'))).data,
    enabled: authenticated,
    // Refetched when the token changes (auth.tsx), not on a timer.
    staleTime: Number.POSITIVE_INFINITY,
    refetchInterval: false,
  });
  const data = authenticated ? me.data : undefined;
  const signedIn = data?.authenticated ?? authenticated;
  return {
    pending: session.status === 'restoring' || (authenticated && me.isPending),
    signedIn,
    role: data?.authenticated ? roleOf(data.roles) : undefined,
    me: data,
    error: authenticated && me.isError && !data ? me.error : undefined,
    refetch: () => void me.refetch(),
  };
}
