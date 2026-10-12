import type * as React from 'react';

import { cn } from '@/lib/utils';

interface SwitchProps extends Omit<React.ComponentProps<'button'>, 'onChange' | 'value'> {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
}

/** An on/off control (WAI-ARIA switch): a button with `role="switch"` and `aria-checked`; Space and Enter toggle it. */
export function Switch({ checked, onCheckedChange, className, disabled, ...props }: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      data-state={checked ? 'checked' : 'unchecked'}
      disabled={disabled}
      onClick={() => {
        onCheckedChange(!checked);
      }}
      className={cn(
        'inline-flex h-5 w-9 shrink-0 items-center rounded-full border border-transparent p-0.5 shadow-xs transition-colors disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:bg-primary data-[state=unchecked]:bg-border-strong',
        className,
      )}
      {...props}
    >
      <span
        aria-hidden="true"
        className={cn(
          'pointer-events-none block size-4 rounded-full bg-card shadow-sm transition-transform motion-reduce:transition-none',
          checked ? 'translate-x-4' : 'translate-x-0',
        )}
      />
    </button>
  );
}
