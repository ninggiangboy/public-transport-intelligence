import type { CSSProperties, ReactNode } from 'react';

interface SplitViewProps {
  list: ReactNode;
  /** The open record; null shows `emptyDetail`. Driven by the URL id param (DOC-34 §4.2). */
  detail: ReactNode | null;
  /** Width of the list column in px. Default 400. Ignored when `detailWidth` is set. */
  listWidth?: number;
  /** A fixed detail column in px, the list taking the rest (Ticketing's table beside a 400 px panel). */
  detailWidth?: number;
  emptyDetail: ReactNode;
}

/** List and detail side by side inside the page (Alerts, Dead letters, Ticketing; DOC-35 §5.5). */
export function SplitView({ list, detail, listWidth = 400, detailWidth, emptyDetail }: SplitViewProps) {
  const style: CSSProperties & Record<`--${string}`, string> =
    detailWidth === undefined ? { '--split-list': `${listWidth}px` } : { '--split-detail': `${detailWidth}px` };
  return (
    <div
      style={style}
      className={
        detailWidth === undefined
          ? 'grid min-h-0 gap-4 lg:grid-cols-[var(--split-list)_minmax(0,1fr)]'
          : 'grid min-h-0 gap-4 lg:grid-cols-[minmax(0,1fr)_var(--split-detail)]'
      }
    >
      <div className="min-w-0">{list}</div>
      <div className="min-w-0 rounded-lg border border-border bg-card shadow-sm">{detail ?? emptyDetail}</div>
    </div>
  );
}
