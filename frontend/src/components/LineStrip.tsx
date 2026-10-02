import type { CSSProperties, ReactNode } from 'react';

import { en } from '@/i18n/en';
import { parseHexColor, toCssRgb } from '@/lib/color';
import type { DelayClass } from '@/lib/delay';
import { cn } from '@/lib/utils';

interface LineStripStop {
  id: string;
  name: string;
  /** "Transfer to 21", "Scheduled 4:31 PM" */
  meta?: ReactNode;
  /** Right-aligned time or delay. */
  eta?: ReactNode;
  /** `passed` stops and the rail up to them are drawn in `--muted-2`. */
  state?: 'passed' | 'current' | 'upcoming';
  /** Larger dot: terminus, interchange. */
  major?: boolean;
}

interface LineStripProps {
  orientation?: 'vertical' | 'horizontal';
  /** Route colour, hex without "#"; default `--primary`. */
  color?: string;
  stops: LineStripStop[];
  /** Horizontal only: colours the run between two stops by delay class (DOC-35 §3.4). */
  segments?: { from: string; to: string; delayClass: DelayClass }[];
  /** For example the selected vehicle between stops: shown right after the stop `atStopId`. */
  marker?: { atStopId: string; label: string };
}

const SEGMENT_COLOR: Record<DelayClass, string> = {
  early: 'var(--delay-early)',
  'on-time': 'var(--delay-on-time)',
  late: 'var(--delay-late)',
  'very-late': 'var(--delay-very-late)',
  unknown: 'var(--delay-unknown)',
};

const PASSED_RAIL = 'var(--muted-2)';

function routeColor(color: string | undefined): string {
  const rgb = parseHexColor(color);
  return rgb ? toCssRgb(rgb) : 'var(--primary)';
}

function Dot({ stop, color }: { stop: LineStripStop; color: string }) {
  const passed = stop.state === 'passed';
  const style: CSSProperties = { borderColor: passed ? PASSED_RAIL : color };
  if (stop.state === 'current') style.backgroundColor = color;
  return (
    <span
      style={style}
      className={cn('relative z-10 shrink-0 rounded-full border-[2.5px] bg-card', stop.major ? 'size-4' : 'size-3')}
      aria-hidden="true"
    />
  );
}

/** Line-and-stop strip (DOC-35 §2.1, §5.9): a 4 px line with white stops ringed in the route colour. */
export function LineStrip({ orientation = 'vertical', color, stops, segments, marker }: LineStripProps) {
  const base = routeColor(color);
  const stateLabel = (stop: LineStripStop) => (stop.state ? en.lineStrip.state[stop.state] : undefined);

  if (orientation === 'horizontal') {
    const nameOf = new Map(stops.map((stop) => [stop.id, stop.name]));
    return (
      <ol className="flex w-full items-start">
        {stops.map((stop, index) => {
          const next = stops[index + 1];
          const segment = next ? segments?.find((s) => s.from === stop.id && s.to === next.id) : undefined;
          const railColor = segment ? SEGMENT_COLOR[segment.delayClass] : stop.state === 'passed' ? PASSED_RAIL : base;
          return (
            <li key={stop.id} className="flex min-w-0 flex-1 flex-col last:flex-none">
              <div className="flex items-center">
                <Dot stop={stop} color={base} />
                {next ? (
                  <span
                    className="h-1 flex-1"
                    style={{ backgroundColor: railColor }}
                    {...(segment
                      ? {
                          role: 'img',
                          'aria-label': en.lineStrip.segment(
                            nameOf.get(stop.id) ?? '',
                            next.name,
                            en.delay.class[segment.delayClass],
                          ),
                        }
                      : { 'aria-hidden': true })}
                  />
                ) : null}
              </div>
              <div className="mt-1.5 pr-2 text-xs">
                <p className={cn('truncate font-medium', stop.state === 'passed' && 'text-muted-foreground')}>
                  {stop.name}
                  {stateLabel(stop) ? <span className="sr-only"> ({stateLabel(stop)})</span> : null}
                </p>
                {stop.eta ? <p className="text-muted-foreground tabular-nums">{stop.eta}</p> : null}
              </div>
              {marker?.atStopId === stop.id ? (
                <p className="mt-1 w-fit rounded-sm bg-primary-soft px-1.5 py-0.5 text-xs font-medium text-primary-soft-fg">
                  {marker.label}
                </p>
              ) : null}
            </li>
          );
        })}
      </ol>
    );
  }

  return (
    <ol className="flex flex-col">
      {stops.map((stop, index) => {
        const last = index === stops.length - 1;
        const railColor = stop.state === 'passed' ? PASSED_RAIL : base;
        return (
          <li key={stop.id} className="relative flex gap-3">
            <div className="flex w-4 shrink-0 flex-col items-center">
              <span className="h-1.5" aria-hidden="true" />
              <Dot stop={stop} color={base} />
              {last ? null : <span className="w-1 flex-1" style={{ backgroundColor: railColor }} aria-hidden="true" />}
            </div>
            <div className="min-w-0 flex-1 pb-4 text-base">
              <div className="flex items-baseline justify-between gap-3">
                <p className={cn('font-medium', stop.state === 'passed' && 'text-muted-foreground')}>
                  {stop.name}
                  {stateLabel(stop) ? <span className="sr-only"> ({stateLabel(stop)})</span> : null}
                </p>
                {stop.eta ? <p className="shrink-0 text-sm text-foreground-2 tabular-nums">{stop.eta}</p> : null}
              </div>
              {stop.meta ? <p className="text-xs text-muted-foreground">{stop.meta}</p> : null}
              {marker?.atStopId === stop.id ? (
                <p className="mt-1.5 w-fit rounded-sm bg-primary-soft px-1.5 py-0.5 text-xs font-medium text-primary-soft-fg">
                  {marker.label}
                </p>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
