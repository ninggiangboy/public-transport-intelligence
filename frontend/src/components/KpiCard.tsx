import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import type { ReactNode } from 'react';

import { AppLink } from '@/components/AppLink';
import { Sparkline } from '@/components/Sparkline';
import { toneClasses, type Tone } from '@/components/tone';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';

interface KpiDelta {
  /** "2.4 pts" */
  value: string;
  direction: 'up' | 'down' | 'flat';
  /** The direction that is an improvement: delta is green when it goes that way, red the other way. */
  good: 'up' | 'down';
  /** "vs last Sunday" */
  caption?: string;
}

interface KpiCardProps {
  label: string;
  value: ReactNode;
  /** Small text next to the value: "%", "s", "/ 642 scheduled". */
  unit?: string;
  delta?: KpiDelta;
  sparkline?: number[];
  /** One line under the value. */
  hint?: ReactNode;
  /** Colours the value only. */
  tone?: Tone;
  href?: string;
}

function Delta({ delta }: { delta: KpiDelta }) {
  const Icon = delta.direction === 'up' ? ArrowUp : delta.direction === 'down' ? ArrowDown : Minus;
  const tone =
    delta.direction === 'flat'
      ? 'text-muted-foreground'
      : delta.direction === delta.good
        ? 'text-tone-success-fg'
        : 'text-tone-danger-fg';
  return (
    <p className="mt-1 flex items-center gap-1 text-xs">
      <span className={cn('inline-flex items-center gap-0.5 font-medium tabular-nums', tone)}>
        <Icon className="size-3" strokeWidth={2} aria-hidden="true" />
        <span className="sr-only">{en.kpi.delta[delta.direction]}</span>
        {delta.value}
      </span>
      {delta.caption ? <span className="text-muted-foreground">{delta.caption}</span> : null}
    </p>
  );
}

/** KPI tile: label, a large train-timetable style number, delta and sparkline (DOC-35 §5.5). */
export function KpiCard({ label, value, unit, delta, sparkline, hint, tone, href }: KpiCardProps) {
  const body = (
    <>
      <p className="text-label font-medium text-muted-foreground">{label}</p>
      <p
        className={cn(
          'mt-1 flex items-baseline gap-1 text-kpi font-semibold tracking-kpi tabular-nums',
          tone && toneClasses(tone).text,
        )}
      >
        {value}
        {unit ? <span className="text-sm font-medium tracking-normal text-muted-foreground">{unit}</span> : null}
      </p>
      {delta ? <Delta delta={delta} /> : null}
      {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
      {sparkline ? (
        <div className="mt-2">
          <Sparkline points={sparkline} label={label} {...(tone ? { tone } : {})} area />
        </div>
      ) : null}
    </>
  );
  const className = 'block rounded-lg border border-border bg-card p-4 text-card-foreground shadow-sm';
  return href ? (
    <AppLink href={href} className={cn(className, 'hover:border-border-strong')}>
      {body}
    </AppLink>
  ) : (
    <div className={className}>{body}</div>
  );
}
