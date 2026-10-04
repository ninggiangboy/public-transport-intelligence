import * as DialogPrimitive from '@radix-ui/react-dialog';
import type * as React from 'react';

import { DialogOverlay } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';

export const Sheet = DialogPrimitive.Root;
export const SheetTrigger = DialogPrimitive.Trigger;
export const SheetClose = DialogPrimitive.Close;
export const SheetTitle = DialogPrimitive.Title;
export const SheetDescription = DialogPrimitive.Description;

const sides = {
  left: 'inset-y-0 left-0 h-full w-[min(280px,85vw)] border-r',
  bottom: 'inset-x-0 bottom-0 max-h-[90svh] rounded-t-xl border-t pb-[env(safe-area-inset-bottom)]',
} as const;

/** A dialog that slides in from an edge: the navigation of narrow screens (DOC-34 §4.2). */
export function SheetContent({
  side = 'left',
  className,
  children,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & { side?: keyof typeof sides }) {
  return (
    <DialogPrimitive.Portal>
      <DialogOverlay />
      <DialogPrimitive.Content
        className={cn(
          'fixed z-(--z-dialog) flex flex-col overflow-y-auto border-border bg-surface text-foreground shadow-lg',
          sides[side],
          className,
        )}
        {...props}
      >
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
