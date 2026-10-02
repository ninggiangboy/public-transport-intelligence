import type { ReactNode } from 'react';

import { RelativeTime } from '@/components/RelativeTime';
import { toneClasses, type ToneOrAccent } from '@/components/tone';
import type { TimeAxis } from '@/lib/use-now';
import { cn } from '@/lib/utils';

interface ActivityTimelineProps {
  items: { id: string; text: ReactNode; at: string; axis: TimeAxis; tone?: ToneOrAccent }[];
}

/** Vertical event list with dots on a thin rail: alert activity, DLQ history, run steps (DOC-35 §5.9). */
export function ActivityTimeline({ items }: ActivityTimelineProps) {
  return (
    <ol className="relative flex flex-col">
      {items.map((item, index) => (
        <li key={item.id} className="relative flex gap-3 pb-4 last:pb-0">
          {index < items.length - 1 ? (
            <span className="absolute top-3.5 bottom-0 left-[5px] w-px bg-border" aria-hidden="true" />
          ) : null}
          <span
            className={cn(
              'relative mt-1.5 size-[11px] shrink-0 rounded-full ring-2 ring-card',
              toneClasses(item.tone ?? 'neutral').solid,
            )}
            aria-hidden="true"
          />
          <div className="min-w-0 text-sm">
            <div>{item.text}</div>
            <div className="text-xs text-muted-foreground">
              <RelativeTime at={item.at} axis={item.axis} />
            </div>
          </div>
        </li>
      ))}
    </ol>
  );
}
