import type { ReactNode } from 'react';

import { toneClasses, type Tone } from '@/components/tone';
import { cn } from '@/lib/utils';

/** Four numbers side by side, split by hairlines (screens/alert-feed §6.1 item 2). */
export function StatRow({ stats }: { stats: { label: string; value: ReactNode; tone?: Tone }[] }) {
  return (
    <dl className="grid grid-cols-2 overflow-hidden rounded-lg border border-border bg-card shadow-xs sm:grid-cols-4">
      {stats.map((stat, index) => (
        <div
          key={stat.label}
          className={cn('px-3.5 py-3', index > 0 && 'border-border sm:border-l', index % 2 === 1 && 'border-l')}
        >
          <dt className="text-xs text-muted-foreground">{stat.label}</dt>
          <dd
            className={cn(
              'mt-1 text-[22px] leading-tight font-semibold tracking-[-0.035em] tabular-nums',
              stat.tone ? toneClasses(stat.tone).text : 'text-foreground',
            )}
          >
            {stat.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** A titled block of the detail panel. */
export function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2.5">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-sm font-semibold tracking-title">{title}</h3>
        {aside ? <span className="text-xs text-muted-foreground">{aside}</span> : null}
      </div>
      {children}
    </section>
  );
}

interface CompareBarProps {
  /** What the bar measures, for screen readers. */
  label: string;
  current: number;
  normal: number;
  /** "+3 min 33 s" */
  currentText: string;
  /** "normal 1 min 1 s" */
  normalText: string;
  tone: Tone;
}

/** The current value as a bar, with a tick where normal sits (screens/alert-feed §6.1 item 4). */
export function CompareBar({ label, current, normal, currentText, normalText, tone }: CompareBarProps) {
  const max = Math.max(current, normal, 1) * 1.15;
  const width = `${Math.max(2, (Math.max(current, 0) / max) * 100)}%`;
  const tick = `${(Math.max(normal, 0) / max) * 100}%`;
  return (
    <div className="flex flex-col gap-1.5">
      <div
        role="img"
        aria-label={`${label}: ${currentText}, ${normalText}`}
        className="relative h-2.5 rounded-full bg-muted"
      >
        <span className={cn('absolute inset-y-0 left-0 rounded-full', toneClasses(tone).solid)} style={{ width }} />
        <span className="absolute -inset-y-1 w-0.5 rounded-full bg-foreground" style={{ left: tick }} />
      </div>
      <div className="flex justify-between text-xs text-muted-foreground tabular-nums">
        <span className={toneClasses(tone).text}>{currentText}</span>
        <span>{normalText}</span>
      </div>
    </div>
  );
}
