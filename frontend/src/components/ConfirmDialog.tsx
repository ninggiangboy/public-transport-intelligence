import { LoaderCircle } from 'lucide-react';
import { useId, useRef, useState, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { en } from '@/i18n/en';
import { describeError } from '@/lib/problem-copy';
import { useReducedMotion } from '@/lib/use-reduced-motion';
import { cn } from '@/lib/utils';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  /** States the consequence of confirming (DOC-34 P-7). */
  description: ReactNode;
  confirmLabel: string;
  tone?: 'default' | 'danger';
  /** A free-text reason the operator must give (discard, resolve). */
  reason?: { label: string; min: number; max: number; placeholder?: string };
  /** The dialog stays open and shows the error when this rejects. */
  onConfirm: (reason?: string) => Promise<void>;
  onOpenChange: (open: boolean) => void;
}

/** Confirmation of an irreversible or far-reaching action (DOC-35 §5.5, DS-07). */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  tone = 'default',
  reason,
  onConfirm,
  onOpenChange,
}: ConfirmDialogProps) {
  const [text, setText] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [failed, setFailed] = useState(false);
  // A ref, not the state, so that a second click in the same tick cannot send a second request (DOC-35 §5.5).
  const inFlight = useRef(false);
  const reducedMotion = useReducedMotion();
  const reasonId = useId();
  const hintId = useId();

  const trimmed = text.trim();
  const reasonValid = !reason || (trimmed.length >= reason.min && trimmed.length <= reason.max);

  const change = (next: boolean) => {
    if (pending) return;
    if (!next) {
      setText('');
      setError(undefined);
      setFailed(false);
    }
    onOpenChange(next);
  };

  const confirm = async () => {
    if (inFlight.current || !reasonValid) return;
    inFlight.current = true;
    setPending(true);
    setFailed(false);
    try {
      await onConfirm(reason ? trimmed : undefined);
      setText('');
      onOpenChange(false);
    } catch (caught: unknown) {
      setError(caught);
      setFailed(true);
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  };

  const described = failed ? describeError(error) : undefined;

  return (
    <Dialog open={open} onOpenChange={change}>
      <DialogContent>
        <div className="flex flex-col gap-3 p-5">
          <DialogTitle className="text-panel font-semibold tracking-title">{title}</DialogTitle>
          <DialogDescription asChild>
            <div className="text-base text-muted-foreground">{description}</div>
          </DialogDescription>
          {reason ? (
            <div className="flex flex-col gap-1.5">
              <label htmlFor={reasonId} className="text-label font-medium text-foreground-2">
                {reason.label}
              </label>
              <Textarea
                id={reasonId}
                value={text}
                maxLength={reason.max}
                placeholder={reason.placeholder}
                aria-describedby={hintId}
                aria-invalid={text.length > 0 && !reasonValid}
                disabled={pending}
                onChange={(event) => {
                  setText(event.target.value);
                }}
              />
              <p id={hintId} className="text-xs text-muted-foreground">
                {reasonValid
                  ? en.confirm.reasonCount(trimmed.length, reason.min, reason.max)
                  : en.confirm.reasonHint(reason.min, reason.max)}
              </p>
            </div>
          ) : null}
          {described ? (
            <div
              role="alert"
              className="rounded-md border border-tone-danger-border bg-tone-danger-bg px-3 py-2 text-sm text-tone-danger-fg"
            >
              <p className="font-semibold">{described.title}</p>
              {described.description ? <p>{described.description}</p> : null}
              {described.traceId ? (
                <p className="font-mono text-xs">
                  {en.error.traceId}: {described.traceId}
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
        <div className="flex items-center justify-end gap-2 rounded-b-dialog border-t border-border bg-surface px-5 py-3">
          <Button
            variant="outline"
            disabled={pending}
            onClick={() => {
              change(false);
            }}
          >
            {en.common.cancel}
          </Button>
          <Button
            variant={tone === 'danger' ? 'destructive' : 'default'}
            disabled={!reasonValid || pending}
            aria-busy={pending}
            className={cn(pending && 'cursor-progress')}
            onClick={() => void confirm()}
          >
            {pending ? <LoaderCircle className={cn(!reducedMotion && 'animate-spin')} aria-hidden="true" /> : null}
            {confirmLabel}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
