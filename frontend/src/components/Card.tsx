import { useId, type ReactNode } from 'react';

import { cn } from '@/lib/utils';

interface CardProps {
  title?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
  /** Band under the content, on `--surface`. */
  footer?: ReactNode;
  /** No shadow: for cards that sit inside another surface. */
  flat?: boolean;
  children: ReactNode;
}

/** White card with an optional head and footer band (DOC-35 §5.9). */
export function Card({ title, meta, actions, footer, flat = false, children }: CardProps) {
  const titleId = useId();
  const hasHead = title !== undefined || meta !== undefined || actions !== undefined;
  return (
    <section
      {...(title !== undefined ? { 'aria-labelledby': titleId } : {})}
      className={cn(
        'overflow-hidden rounded-lg border border-border bg-card text-card-foreground',
        !flat && 'shadow-sm',
      )}
    >
      {hasHead ? (
        <header className="flex items-center gap-3 px-4 pt-3.5 pb-1">
          <div className="min-w-0 flex-1">
            {title !== undefined ? (
              <h3 id={titleId} className="text-base font-semibold tracking-title">
                {title}
              </h3>
            ) : null}
          </div>
          {meta ? <div className="text-xs text-muted-foreground">{meta}</div> : null}
          {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </header>
      ) : null}
      <div className="px-4 py-3.5">{children}</div>
      {footer ? <footer className="border-t border-border bg-surface px-4 py-2.5 text-sm">{footer}</footer> : null}
    </section>
  );
}
