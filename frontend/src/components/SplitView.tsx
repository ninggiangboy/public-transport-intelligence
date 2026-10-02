import type { CSSProperties, ReactNode } from 'react';

interface SplitViewProps {
  list: ReactNode;
  /** The open record; null shows `emptyDetail`. Driven by the URL id param (DOC-34 §4.2). */
  detail: ReactNode | null;
  /** Width of the list column in px. Default 400. */
  listWidth?: number;
  emptyDetail: ReactNode;
}

/** List and detail side by side inside the page (Alerts, Dead letters, Ticketing; DOC-35 §5.5). */
export function SplitView({ list, detail, listWidth = 400, emptyDetail }: SplitViewProps) {
  const style: CSSProperties & Record<`--${string}`, string> = { '--split-list': `${listWidth}px` };
  return (
    <div style={style} className="grid min-h-0 gap-4 lg:grid-cols-[var(--split-list)_minmax(0,1fr)]">
      <div className="min-w-0">{list}</div>
      <div className="min-w-0 rounded-lg border border-border bg-card shadow-sm">{detail ?? emptyDetail}</div>
    </div>
  );
}
