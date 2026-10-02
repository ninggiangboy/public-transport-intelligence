import * as DialogPrimitive from '@radix-ui/react-dialog';
import type * as React from 'react';

import { cn } from '@/lib/utils';

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;
export const DialogTitle = DialogPrimitive.Title;
export const DialogDescription = DialogPrimitive.Description;

export function DialogOverlay({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      className={cn('fixed inset-0 z-(--z-dialog) bg-scrim backdrop-blur-[2px]', className)}
      {...props}
    />
  );
}

/** Centred dialog, 440 px wide, 14 px radius (DOC-35 §5.5). Radix traps focus and returns it to the opener. */
export function DialogContent({ className, children, ...props }: React.ComponentProps<typeof DialogPrimitive.Content>) {
  return (
    <DialogPrimitive.Portal>
      <DialogOverlay />
      <DialogPrimitive.Content
        className={cn(
          'fixed top-1/2 left-1/2 z-(--z-dialog) w-[min(440px,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-dialog border border-border bg-card text-card-foreground shadow-lg',
          className,
        )}
        {...props}
      >
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
