import { X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { en } from '@/i18n/en';

interface KeyboardShortcutsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** The shortcuts of DOC-34 §4.3, opened from the account menu. */
export function KeyboardShortcutsDialog({ open, onOpenChange }: KeyboardShortcutsDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="p-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <DialogTitle className="text-panel font-semibold tracking-title">{en.shortcuts.title}</DialogTitle>
            <DialogDescription className="mt-1 text-sm text-muted-foreground">
              {en.shortcuts.description}
            </DialogDescription>
          </div>
          <DialogClose asChild>
            <Button variant="ghost" size="icon-sm" aria-label={en.common.close}>
              <X aria-hidden="true" />
            </Button>
          </DialogClose>
        </div>
        <dl className="mt-4 divide-y divide-border">
          {en.shortcuts.rows.map((row) => (
            <div key={row.label} className="flex items-center justify-between gap-4 py-2 text-sm">
              <dt>{row.label}</dt>
              <dd className="flex gap-1">
                {row.keys.map((key) => (
                  <kbd
                    key={key}
                    className="rounded-[5px] border border-b-2 border-border bg-surface px-1.5 py-0.5 font-mono text-[10.5px] text-muted-foreground"
                  >
                    {key}
                  </kbd>
                ))}
              </dd>
            </div>
          ))}
        </dl>
      </DialogContent>
    </Dialog>
  );
}
