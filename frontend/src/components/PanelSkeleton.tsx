import { useEffect, useState } from 'react';

import { Skeleton } from '@/components/ui/skeleton';
import { en } from '@/i18n/en';

interface PanelSkeletonProps {
  variant: 'table' | 'chart' | 'list' | 'detail';
  /** Rows of `table` (default 8) and `list` (default 5). */
  rows?: number;
  /** Shown only if loading takes longer than this, so quick responses do not flash a skeleton (DOC-37 §2.1). */
  delayMs?: number;
}

const DEFAULT_ROWS = { table: 8, list: 5 } as const;

function Rows({ count, className }: { count: number; className: string }) {
  return Array.from({ length: count }, (_, index) => <Skeleton key={index} className={className} />);
}

/** Placeholder with the shape of the content to come; the panel is busy to assistive technology (DOC-35 §5.4). */
export function PanelSkeleton({ variant, rows, delayMs = 150 }: PanelSkeletonProps) {
  const [visible, setVisible] = useState(delayMs <= 0);
  useEffect(() => {
    if (delayMs <= 0) return;
    const timer = setTimeout(() => {
      setVisible(true);
    }, delayMs);
    return () => {
      clearTimeout(timer);
    };
  }, [delayMs]);

  if (!visible) return null;
  return (
    <div aria-busy="true" className="flex flex-col gap-3" data-variant={variant}>
      <span role="status" className="sr-only">
        {en.states.loading}
      </span>
      {variant === 'table' ? (
        <>
          <Skeleton className="h-7 w-full" />
          <Rows count={rows ?? DEFAULT_ROWS.table} className="h-6 w-full" />
        </>
      ) : null}
      {variant === 'list' ? <Rows count={rows ?? DEFAULT_ROWS.list} className="h-10 w-full" /> : null}
      {variant === 'chart' ? (
        <div className="flex h-56 items-end gap-2 border-b border-l border-border px-2 pb-1">
          {[40, 65, 50, 80, 55, 90, 70, 45].map((height, index) => (
            <Skeleton key={index} className="w-full" style={{ height: `${height}%` }} />
          ))}
        </div>
      ) : null}
      {variant === 'detail' ? (
        <>
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="h-4 w-1/3" />
          <Rows count={4} className="h-4 w-full" />
        </>
      ) : null}
    </div>
  );
}
