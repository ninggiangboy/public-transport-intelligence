import * as ProgressPrimitive from '@radix-ui/react-progress';
import type * as React from 'react';

import { cn } from '@/lib/utils';

// Progress rail: `--muted-2` track, 6 px high (DOC-35 §3.1, DOC-37 §2.1).
export function Progress({
  className,
  value,
  indicatorClassName,
  ...props
}: React.ComponentProps<typeof ProgressPrimitive.Root> & { indicatorClassName?: string }) {
  return (
    <ProgressPrimitive.Root
      value={value}
      className={cn('relative h-1.5 w-full overflow-hidden rounded-full bg-muted-2', className)}
      {...props}
    >
      <ProgressPrimitive.Indicator
        className={cn('h-full w-full flex-1 bg-primary motion-safe:transition-transform', indicatorClassName)}
        style={{ transform: `translateX(-${100 - Math.min(100, Math.max(0, value ?? 0))}%)` }}
      />
    </ProgressPrimitive.Root>
  );
}
