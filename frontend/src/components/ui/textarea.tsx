import type * as React from 'react';

import { cn } from '@/lib/utils';

export function Textarea({ className, ...props }: React.ComponentProps<'textarea'>) {
  return (
    <textarea
      className={cn(
        'min-h-20 w-full rounded-md border border-input bg-card px-3 py-2 text-base shadow-xs placeholder:text-subtle-foreground disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive',
        className,
      )}
      {...props}
    />
  );
}
