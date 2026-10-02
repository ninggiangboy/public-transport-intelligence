import { Lock } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';

interface NoAccessStateProps {
  requiredRole: 'viewer' | 'operator';
  signedIn: boolean;
  /** Without a handler the matching button is left out. */
  onSignIn?: () => void;
  onGoToOverview?: () => void;
}

/** A page the user may not open: sign in when anonymous, "no access" when signed in (DOC-37 §2.5). */
export function NoAccessState({ requiredRole, signedIn, onSignIn, onGoToOverview }: NoAccessStateProps) {
  const copy = en.states.noAccess;
  const title = signedIn ? copy.forbiddenTitle : copy.signInTitle;
  const body = signedIn ? copy.forbiddenBody : requiredRole === 'operator' ? copy.operatorBody : copy.viewerBody;
  const action = signedIn
    ? onGoToOverview && { label: copy.goToOverview, onClick: onGoToOverview }
    : onSignIn && { label: en.common.signIn, onClick: onSignIn };
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
      <span className="flex size-11 items-center justify-center rounded-lg border border-border bg-card text-muted-foreground shadow-sm">
        <Lock className="size-5" strokeWidth={1.75} aria-hidden="true" />
      </span>
      <div className="flex flex-col gap-1">
        <h3 className="text-base font-semibold tracking-title">{title}</h3>
        <p className="max-w-80 text-sm text-muted-foreground">{body}</p>
      </div>
      {action ? (
        <Button size="sm" onClick={action.onClick}>
          {action.label}
        </Button>
      ) : null}
    </div>
  );
}
