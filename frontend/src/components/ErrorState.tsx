import { CircleX, TriangleAlert } from 'lucide-react';

import { CopyButton } from '@/components/CopyButton';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { describeError, type ErrorAction } from '@/lib/problem-copy';
import { useRelative, type TimeAxis } from '@/lib/use-now';

interface ErrorStateProps {
  /** An `ApiError`, a failed fetch (`TypeError`) or anything else thrown; read by shape, not by class. */
  error: unknown;
  /** `block` replaces a panel that has nothing to show; `inline` is a strip above stale data (DOC-34 P-3). */
  variant: 'block' | 'inline';
  onRetry?: () => void;
  /** Name of what failed: "{Thing} not found". */
  thing?: string;
  /** `block`: "Couldn't load {panel}" for network errors and 5xx. */
  panel?: string;
  /** `inline`: when the data on display was fetched, for "Showing data from ...". */
  dataAsOf?: string;
  axis?: TimeAxis;
  /** Handlers for the buttons some slugs ask for; a button shows only when its handler is given. */
  actions?: Partial<Record<Exclude<ErrorAction, 'retry' | 'reload'>, () => void>>;
}

function InlineAge({ at, axis, title }: { at: string; axis: TimeAxis; title: string }) {
  const relative = useRelative(at, axis);
  return <>{en.error.showingDataFrom(title, relative)}</>;
}

/** Error of a panel or a page, in the words of DOC-37 §2.3, with the trace id to quote when reporting it. */
export function ErrorState({
  error,
  variant,
  onRetry,
  thing,
  panel,
  dataAsOf,
  axis = 'event',
  actions,
}: ErrorStateProps) {
  const described = describeError(error, thing ? { thing } : {});
  const reload = () => {
    window.location.reload();
  };
  const handlers: Record<ErrorAction, (() => void) | undefined> = {
    retry: onRetry,
    reload: onRetry ?? reload,
    signIn: actions?.signIn,
    goBack: actions?.goBack,
    viewReplay: actions?.viewReplay,
  };
  const action = described.action;
  const actionLabel =
    action === 'retry'
      ? en.common.retry
      : action === 'reload'
        ? en.common.reload
        : action === 'signIn'
          ? en.common.signIn
          : action === 'goBack'
            ? en.common.goBack
            : action === 'viewReplay'
              ? en.error.viewReplay
              : undefined;
  const actionHandler = action ? handlers[action] : undefined;
  // A network failure or a server fault says which panel failed (DOC-37 §2.3).
  const title =
    variant === 'block' &&
    (described.network || described.slug === 'internal-error' || described.slug === 'service-unavailable')
      ? en.error.couldntLoad(panel ?? en.error.panelFallback)
      : described.title;
  const showRetry = onRetry !== undefined && action !== 'retry' && action !== 'reload';

  if (variant === 'inline') {
    return (
      <div
        role="alert"
        className="flex items-center gap-3 rounded-[10px] border border-tone-warning-border bg-tone-warning-bg px-3 py-2 text-sm text-tone-warning-fg"
      >
        <TriangleAlert className="size-4 shrink-0" strokeWidth={1.75} aria-hidden="true" />
        <p className="min-w-0 flex-1">
          {dataAsOf ? <InlineAge at={dataAsOf} axis={axis} title={title} /> : title}
          {described.traceId ? (
            <span className="ml-2 font-mono text-xs opacity-90">
              {en.error.traceId}: {described.traceId}
            </span>
          ) : null}
        </p>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {en.common.retry}
          </Button>
        ) : null}
      </div>
    );
  }

  return (
    <div role="alert" className="flex flex-col items-center gap-3 px-6 py-10 text-center">
      <span className="flex size-11 items-center justify-center rounded-lg border border-tone-danger-border bg-tone-danger-bg text-tone-danger-fg">
        <CircleX className="size-5" strokeWidth={1.75} aria-hidden="true" />
      </span>
      <div className="flex max-w-96 flex-col gap-1">
        <h3 className="text-base font-semibold tracking-title">{title}</h3>
        {described.description ? <p className="text-sm text-muted-foreground">{described.description}</p> : null}
        {described.fieldErrors.length > 0 ? (
          <ul className="mt-1 text-left text-sm text-muted-foreground">
            {described.fieldErrors.map((item) => (
              <li key={`${item.field}:${item.message}`}>
                {item.field ? en.error.fieldError(item.field, item.message) : item.message}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
      {described.traceId ? (
        <p className="flex items-center gap-1 text-xs text-muted-foreground">
          <span>{en.error.traceId}</span>
          <code className="font-mono">{described.traceId}</code>
          <CopyButton value={described.traceId} label={en.error.copyTrace} />
        </p>
      ) : null}
      <div className="flex gap-2">
        {actionHandler && actionLabel ? (
          <Button variant="outline" size="sm" onClick={actionHandler}>
            {actionLabel}
          </Button>
        ) : null}
        {showRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {en.common.retry}
          </Button>
        ) : null}
      </div>
    </div>
  );
}
