import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import type * as React from 'react';

import { cn } from '@/lib/utils';

// Heights follow DOC-35 §4.2: 34 px controls, 28 px small, 40 px large.
export const buttonVariants = cva(
  'inline-flex shrink-0 items-center justify-center gap-1.5 rounded-md text-base font-medium whitespace-nowrap shadow-xs disabled:pointer-events-none disabled:opacity-50 motion-safe:transition-colors [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*="size-"])]:size-4',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:bg-primary-hover',
        outline: 'border border-border-strong bg-card text-foreground hover:bg-muted',
        ghost: 'text-foreground shadow-none hover:bg-muted',
        destructive: 'bg-destructive text-destructive-foreground hover:opacity-90',
        link: 'text-primary underline-offset-4 shadow-none hover:underline',
      },
      size: {
        default: 'h-8.5 px-3.5',
        sm: 'h-7 px-2.5 text-sm',
        lg: 'h-10 px-5',
        icon: 'size-8.5',
        'icon-sm': 'size-7',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
);

interface ButtonProps extends React.ComponentProps<'button'>, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({ className, variant, size, asChild = false, type, ...props }: ButtonProps) {
  const Comp = asChild ? Slot : 'button';
  return (
    <Comp
      {...(asChild ? {} : { type: type ?? 'button' })}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  );
}
