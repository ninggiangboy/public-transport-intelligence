import { Inbox, type LucideIcon } from 'lucide-react';

import { Button } from '@/components/ui/button';

interface EmptyStateProps {
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
  /** A neutral icon (`Inbox`, `SearchX`...). Default `Inbox`. */
  icon?: LucideIcon;
}

/** Nothing to show, and why. No illustration (DOC-37 §2.2). */
export function EmptyState({ title, description, action, icon: Icon = Inbox }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
      <span className="flex size-11 items-center justify-center rounded-lg border border-border bg-card text-muted-foreground shadow-sm">
        <Icon className="size-5" strokeWidth={1.75} aria-hidden="true" />
      </span>
      <div className="flex flex-col gap-1">
        <h3 className="text-base font-semibold tracking-title">{title}</h3>
        {description ? <p className="max-w-80 text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {action ? (
        <Button variant="outline" size="sm" onClick={action.onClick}>
          {action.label}
        </Button>
      ) : null}
    </div>
  );
}
