import type { LucideIcon } from 'lucide-react';

import { toneClasses, type ToneOrAccent } from '@/components/tone';
import { cn } from '@/lib/utils';

interface ToneBadgeProps {
  tone: ToneOrAccent;
  icon?: LucideIcon;
  /** The icon turns (running states). */
  spin?: boolean;
  size?: 'sm' | 'md';
  /** Hides the text; the label stays as the accessible name (DOC-35 §8, WCAG 1.4.1). */
  iconOnly?: boolean;
  label: string;
  className?: string;
}

/** Badge of DOC-35 §5.1: light background, dark coloured text, 22 px high, 6 px radius, 12 px icon and 12 px text. */
export function ToneBadge({
  tone,
  icon: Icon,
  spin = false,
  size = 'md',
  iconOnly = false,
  label,
  className,
}: ToneBadgeProps) {
  return (
    <span
      {...(iconOnly ? { role: 'img', 'aria-label': label, title: label } : {})}
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-sm border text-xs font-medium whitespace-nowrap',
        size === 'sm' ? 'h-5 px-1.5' : 'h-5.5 px-2',
        toneClasses(tone).surface,
        className,
      )}
    >
      {Icon ? <Icon className={cn('size-3', spin && 'animate-spin')} strokeWidth={1.75} aria-hidden="true" /> : null}
      {iconOnly ? null : label}
    </span>
  );
}
