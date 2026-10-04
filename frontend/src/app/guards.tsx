import { useNavigate } from '@tanstack/react-router';
import type { ReactNode } from 'react';

import { hasRole, useAccess, type Role } from '@/app/access';
import { useEnv } from '@/app/env-context';
import { useSession } from '@/app/session';
import { ErrorState } from '@/components/ErrorState';
import { NoAccessState } from '@/components/NoAccessState';
import { PanelSkeleton } from '@/components/PanelSkeleton';

/**
 * Renders `children` only for `role` and above; otherwise the states of DOC-37 §2.5, inside the page. The page's
 * queries live in `children`, so nothing is requested for a user who may not see it (UX-03). Hiding is a convenience:
 * the API checks every request (DOC-27 §4).
 */
export function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const access = useAccess();
  const session = useSession();
  const navigate = useNavigate();
  if (access.pending) return <PanelSkeleton variant="detail" />;
  if (access.error) return <ErrorState error={access.error} variant="block" onRetry={access.refetch} />;
  if (hasRole(access, role)) return children;
  return (
    <NoAccessState
      requiredRole={role}
      signedIn={access.signedIn}
      signInAvailable={session.available}
      onSignIn={() => void session.signIn()}
      onGoToOverview={access.role ? () => void navigate({ to: '/overview' }) : undefined}
    />
  );
}

/** Demo control: operators, and only when `env.js` turns it on (DOC-34 §10). */
export function RequireDemo({ children, fallback }: { children: ReactNode; fallback: ReactNode }) {
  const appEnv = useEnv();
  if (!appEnv.demoControl) return fallback;
  return <RequireRole role="operator">{children}</RequireRole>;
}
