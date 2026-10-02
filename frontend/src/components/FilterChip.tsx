import { ChevronDown } from 'lucide-react';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/utils';

interface FilterChipProps extends Omit<ComponentProps<'button'>, 'children'> {
  /** True while a value is applied: the chip turns solid (DOC-35 §5.3). */
  active: boolean;
  /** The chip's text: the label, or "Label: value". */
  text: string;
}

/** Round 28 px filter chip; applied filters are filled with `--foreground` and read "Severity: High". */
export function FilterChip({ active, text, className, ...props }: FilterChipProps) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex h-7 max-w-full items-center gap-1 rounded-full border px-3 text-label font-medium motion-safe:transition-colors',
        active
          ? 'border-foreground bg-foreground text-card'
          : 'border-border-strong bg-card text-foreground-2 hover:bg-muted',
        className,
      )}
      {...props}
    >
      <span className="truncate">{text}</span>
      <ChevronDown className="size-3.5 shrink-0" aria-hidden="true" />
    </button>
  );
}
