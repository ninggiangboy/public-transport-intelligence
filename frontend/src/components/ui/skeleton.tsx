import type * as React from 'react';

import { cn } from '@/lib/utils';

/** Shimmer bar, 1.6 s; the animation stops under prefers-reduced-motion (globals.css). */
export function Skeleton({ className, ...props }: React.ComponentProps<'div'>) {
  return <div aria-hidden="true" className={cn('animate-shimmer rounded-md', className)} {...props} />;
}
