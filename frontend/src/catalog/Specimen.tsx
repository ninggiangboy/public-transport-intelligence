import type { ReactNode } from 'react';

import { catalogCopy } from '@/i18n/catalog';
import { cn } from '@/lib/utils';

interface ThemePanelProps {
  theme: 'light' | 'dark';
  children: ReactNode;
  className?: string;
}

/** A subtree re-themed by the `.light` / `.dark` token scope of tokens.css. */
export function ThemePanel({ theme, children, className }: ThemePanelProps) {
  return (
    <div className={cn(theme, 'min-w-0 rounded-xl border border-border bg-background p-4 text-foreground', className)}>
      <p className="mb-3 text-xs font-medium text-muted-foreground">{catalogCopy.themes[theme]}</p>
      {children}
    </div>
  );
}

interface SpecimenProps {
  /** Heading: the component names. */
  name: string;
  children: ReactNode;
  /** Shown once instead of twice: content that opens portals themed by the page theme. */
  single?: boolean;
}

/** One catalogue entry: the same content in light and dark, side by side. */
export function Specimen({ name, children, single = false }: SpecimenProps) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-panel font-semibold tracking-title">{name}</h3>
      {single ? (
        <div className="rounded-xl border border-border bg-background p-4">{children}</div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <ThemePanel theme="light">{children}</ThemePanel>
          <ThemePanel theme="dark">{children}</ThemePanel>
        </div>
      )}
    </section>
  );
}

/** A labelled row inside a specimen. */
export function Row({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <div className="mb-3 last:mb-0">
      {label ? <p className="mb-1.5 text-xs font-medium text-muted-foreground">{label}</p> : null}
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}
