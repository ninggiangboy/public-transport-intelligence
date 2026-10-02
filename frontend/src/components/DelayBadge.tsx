import { ToneBadge } from '@/components/ToneBadge';
import type { Tone } from '@/components/tone';
import { en } from '@/i18n/en';
import { delayClass, type DelayClass } from '@/lib/delay';
import { formatDelaySeconds } from '@/lib/format';
import { cn } from '@/lib/utils';

interface DelayBadgeProps {
  delaySeconds?: number | null;
  /** `text`: a dot of the class colour and the label; `chip`: a tone badge. */
  variant?: 'text' | 'chip';
}

const CHIP_TONE: Record<DelayClass, Tone> = {
  early: 'info',
  'on-time': 'success',
  late: 'warning',
  'very-late': 'danger',
  unknown: 'neutral',
};

const DOT: Record<DelayClass, string> = {
  early: 'bg-delay-early',
  'on-time': 'bg-delay-on-time',
  late: 'bg-delay-late',
  'very-late': 'bg-delay-very-late',
  unknown: 'bg-delay-unknown',
};

/** Delay class of DOC-35 §3.4 as a word, never as colour alone. The exact delay is the tooltip. */
export function DelayBadge({ delaySeconds, variant = 'text' }: DelayBadgeProps) {
  const cls = delayClass(delaySeconds);
  const label = en.delay.class[cls];
  const title = typeof delaySeconds === 'number' ? formatDelaySeconds(delaySeconds) : undefined;
  if (variant === 'chip') {
    return (
      <span title={title}>
        <ToneBadge tone={CHIP_TONE[cls]} label={label} />
      </span>
    );
  }
  return (
    <span title={title} className="inline-flex items-center gap-1.5 text-xs font-medium text-foreground-2">
      <span className={cn('size-2 rounded-full', DOT[cls])} aria-hidden="true" />
      {label}
    </span>
  );
}
