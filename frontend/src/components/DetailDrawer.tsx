import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { DialogClose, DialogOverlay, DialogTitle } from '@/components/ui/dialog';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';

interface DetailDrawerProps {
  title: ReactNode;
  open: boolean;
  onClose: () => void;
  /** Width in px. Default 520. */
  width?: 520 | 680;
  footer?: ReactNode;
  children: ReactNode;
}

/** Panel that slides in from the right; focus moves into it and returns to the opener on close (DOC-35 §5.5, §8). */
export function DetailDrawer({ title, open, onClose, width = 520, footer, children }: DetailDrawerProps) {
  return (
    <DialogPrimitive.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
    >
      <DialogPrimitive.Portal>
        <DialogOverlay className="z-(--z-drawer) bg-transparent backdrop-blur-none" />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          style={{ width }}
          className={cn(
            'fixed inset-y-0 right-0 z-(--z-drawer) flex max-w-full flex-col border-l border-border bg-card text-card-foreground shadow-lg',
            'motion-safe:duration-[220ms] motion-safe:data-[state=closed]:animate-out motion-safe:data-[state=closed]:slide-out-to-right motion-safe:data-[state=open]:animate-in motion-safe:data-[state=open]:slide-in-from-right',
          )}
        >
          <header className="flex items-start gap-3 border-b border-border px-5 py-4">
            <DialogTitle className="min-w-0 flex-1 text-panel font-semibold tracking-title">{title}</DialogTitle>
            <DialogClose asChild>
              <Button variant="ghost" size="icon-sm" aria-label={en.drawer.close}>
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </header>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
          {footer ? (
            <footer className="flex items-center justify-end gap-2 border-t border-border bg-surface px-5 py-3">
              {footer}
            </footer>
          ) : null}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
