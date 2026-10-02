import { Check, Copy } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';

interface CopyButtonProps {
  value: string;
  /** The accessible name, for example "Copy trace ID". */
  label: string;
  /** Visible text next to the icon; without it the button is icon-only. */
  children?: ReactNode;
  className?: string;
}

const FEEDBACK_MS = 1500;

/** Copies `value` to the clipboard and says so for 1.5 s, visibly and to screen readers (DOC-35 §5.5). */
export function CopyButton({ value, label, children, className }: CopyButtonProps) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(
    () => () => {
      clearTimeout(timer.current);
    },
    [],
  );

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // Clipboard access can be refused (insecure origin, permissions); the button then stays as it was.
      return;
    }
    setCopied(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setCopied(false);
    }, FEEDBACK_MS);
  };

  return (
    <>
      <Button
        variant="ghost"
        size={children ? 'sm' : 'icon-sm'}
        aria-label={label}
        className={cn('text-muted-foreground', className)}
        onClick={() => void copy()}
      >
        {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
        {children}
      </Button>
      <span role="status" className="sr-only">
        {copied ? en.common.copied : ''}
      </span>
    </>
  );
}
