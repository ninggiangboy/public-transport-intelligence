import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

interface KeyValueListProps {
  items: { label: string; value: ReactNode }[];
  columns?: 1 | 2;
}

/** Label and value pairs as a description list (DOC-35 §5.5). */
export function KeyValueList({ items, columns = 1 }: KeyValueListProps) {
  return (
    <dl className={cn('grid gap-x-8 gap-y-3', columns === 2 ? 'sm:grid-cols-2' : 'grid-cols-1')}>
      {items.map((item) => (
        <div key={item.label} className="min-w-0">
          <dt className="text-label font-medium text-muted-foreground">{item.label}</dt>
          <dd className="mt-0.5 text-base break-words text-foreground">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
