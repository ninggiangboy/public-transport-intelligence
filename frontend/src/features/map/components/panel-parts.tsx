import { X } from 'lucide-react';
import type { ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { mapCopy } from '@/i18n/map';
import { cn } from '@/lib/utils';

// Building blocks of the detail panels (screens/live-map §4.1), drawn after the prototype's `.metric` and
// `.panel-sec` (live-map.html).

const copy = mapCopy.map.panel;

export type PanelKind = 'vehicle' | 'bunching' | 'disruption';

/** The kind of object on show, and the close button. */
export function PanelBar({ kind, onClose }: { kind: PanelKind; onClose: () => void }) {
  return (
    <div className="flex items-center justify-between px-3 pt-3">
      <span className="rounded-md bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
        {copy.kind[kind]}
      </span>
      <Button variant="ghost" size="icon" aria-label={copy.close} onClick={onClose} className="size-8">
        <X aria-hidden="true" />
      </Button>
    </div>
  );
}

/** One of the small numbers in a row of three. */
export function Metric({
  label,
  value,
  unit,
  tone,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  tone?: 'danger' | 'warning';
}) {
  return (
    <div className="min-w-0 rounded-[10px] border border-border bg-surface px-[11px] py-[9px]">
      <dt className="truncate text-[11.5px] text-muted-foreground">{label}</dt>
      <dd
        className={cn(
          'mt-0.5 truncate text-[15px] font-semibold tracking-[-0.02em] tabular-nums',
          tone === 'danger' && 'text-tone-danger-fg',
          tone === 'warning' && 'text-tone-warning-fg',
        )}
      >
        {value}
        {unit ? <span className="ml-1 text-xs font-medium text-muted-foreground">{unit}</span> : null}
      </dd>
    </div>
  );
}

export function Metrics({ children }: { children: ReactNode }) {
  return <dl className="grid grid-cols-3 gap-2 px-[18px] pb-4">{children}</dl>;
}

/** A section under a top border. */
export function PanelSection({ title, aside, children }: { title?: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="border-t border-border px-[18px] py-4" aria-label={title}>
      {title || aside ? (
        <div className="mb-3 flex items-center justify-between gap-3">
          {title ? <h3 className="text-[13.5px] font-semibold">{title}</h3> : <span />}
          {aside}
        </div>
      ) : null}
      {children}
    </section>
  );
}

/** Buttons at the foot of a panel. */
export function PanelFooter({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap gap-2 border-t border-border px-[18px] py-3">{children}</div>;
}

/** The object went away while open: a lost bus, a recomputed episode (screens/live-map §6). */
export function PanelNotice({ children }: { children: ReactNode }) {
  return <p className="px-[18px] py-6 text-sm text-muted-foreground">{children}</p>;
}
