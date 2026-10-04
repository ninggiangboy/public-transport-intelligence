import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { CircleX, LoaderCircle } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useSession } from '@/app/session';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';

// Keycloak redirects here after sign-in with `code` and `state` (DOC-34 §9.3).
export const Route = createFileRoute('/auth/callback')({
  component: AuthCallback,
});

function AuthCallback() {
  const session = useSession();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  useDocumentTitle(failed ? en.auth.callbackFailedTitle : en.auth.signingIn);

  useEffect(() => {
    let active = true;
    session
      .completeSignIn()
      .then((returnTo) => {
        if (active) void navigate({ href: returnTo, replace: true });
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, [session, navigate]);

  if (failed) {
    return (
      <div
        role="alert"
        className="mx-auto flex max-w-md flex-1 flex-col items-center justify-center gap-3 py-10 text-center"
      >
        <span className="flex size-11 items-center justify-center rounded-lg border border-tone-danger-border bg-tone-danger-bg text-tone-danger-fg">
          <CircleX className="size-5" strokeWidth={1.75} aria-hidden="true" />
        </span>
        <h1 className="text-page font-semibold tracking-title">{en.auth.callbackFailedTitle}</h1>
        <p className="text-muted-foreground">{en.auth.callbackFailedBody}</p>
        <Button variant="outline" onClick={() => void navigate({ to: '/', replace: true })}>
          {en.auth.tryAgain}
        </Button>
      </div>
    );
  }
  return (
    <div className="flex flex-1 items-center justify-center gap-2 py-10 text-muted-foreground" role="status">
      <LoaderCircle className="size-4 motion-safe:animate-spin" aria-hidden="true" />
      <h1 className="text-base">{en.auth.signingIn}</h1>
    </div>
  );
}
