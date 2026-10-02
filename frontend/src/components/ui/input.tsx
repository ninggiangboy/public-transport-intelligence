import type * as React from 'react';

import { cn } from '@/lib/utils';

export function Input({ className, type = 'text', ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      type={type}
      className={cn(
        'h-8.5 w-full min-w-0 rounded-md border border-input bg-card px-3 text-base shadow-xs placeholder:text-subtle-foreground disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive',
        className,
      )}
      {...props}
    />
  );
}
