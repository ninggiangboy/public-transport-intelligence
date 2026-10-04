import { useRef, useState, type ReactNode } from 'react';

import { mapCopy } from '@/i18n/map';
import { cn } from '@/lib/utils';

// The bottom sheet of the mobile map (DOC-34 §6, screens/live-map §4.2): three heights, 96 px, 50 % and 90 % of the
// map. Drag the handle, or focus it and press Enter for the next height.

export type SheetSnap = 'peek' | 'half' | 'full';

const ORDER: readonly SheetSnap[] = ['peek', 'half', 'full'];
const PEEK_PX = 96;

function heightOf(snap: SheetSnap, container: number): number {
  if (snap === 'peek') return PEEK_PX;
  return container * (snap === 'half' ? 0.5 : 0.9);
}

interface BottomSheetProps {
  snap: SheetSnap;
  onSnapChange: (snap: SheetSnap) => void;
  /** Accessible name of the sheet. */
  label: string;
  children: ReactNode;
}

export function BottomSheet({ snap, onSnapChange, label, children }: BottomSheetProps) {
  const sheet = useRef<HTMLElement>(null);
  const [drag, setDrag] = useState<{ startY: number; startHeight: number; height: number }>();

  const containerHeight = () => sheet.current?.parentElement?.clientHeight ?? globalThis.innerHeight;

  const onPointerDown = (event: React.PointerEvent) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    const height = sheet.current?.getBoundingClientRect().height ?? PEEK_PX;
    setDrag({ startY: event.clientY, startHeight: height, height });
  };
  const onPointerMove = (event: React.PointerEvent) => {
    if (!drag) return;
    const max = containerHeight() * 0.9;
    const height = Math.min(max, Math.max(PEEK_PX, drag.startHeight + drag.startY - event.clientY));
    setDrag({ ...drag, height });
  };
  const onPointerUp = () => {
    if (!drag) return;
    const container = containerHeight();
    const moved = Math.abs(drag.height - drag.startHeight) > 6;
    // A tap without movement steps to the next height, like Enter.
    const nearest = moved
      ? ORDER.reduce((best, candidate) =>
          Math.abs(heightOf(candidate, container) - drag.height) < Math.abs(heightOf(best, container) - drag.height)
            ? candidate
            : best,
        )
      : ORDER[(ORDER.indexOf(snap) + 1) % ORDER.length];
    setDrag(undefined);
    if (nearest) onSnapChange(nearest);
  };

  const style =
    drag === undefined
      ? { height: snap === 'peek' ? `${PEEK_PX}px` : snap === 'half' ? '50%' : '90%' }
      : { height: `${drag.height}px` };

  return (
    <section
      ref={sheet}
      aria-label={label}
      style={style}
      className={cn(
        'absolute inset-x-0 bottom-0 z-20 flex flex-col overflow-hidden rounded-t-[20px] bg-card shadow-[0_-8px_30px_-8px_rgb(16_18_27/0.22)]',
        drag === undefined && 'motion-safe:transition-[height] motion-safe:duration-250 motion-safe:ease-out',
      )}
    >
      <button
        type="button"
        aria-label={mapCopy.map.mobile.resize}
        className="grid h-11 w-full shrink-0 touch-none place-items-center"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={() => {
          setDrag(undefined);
        }}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault();
          onSnapChange(ORDER[(ORDER.indexOf(snap) + 1) % ORDER.length] ?? 'peek');
        }}
      >
        <span className="h-[5px] w-10 rounded-full bg-muted-2" aria-hidden="true" />
      </button>
      {/* Focusable, so that the list scrolls from the keyboard too (axe scrollable-region-focusable). */}
      <div
        tabIndex={0}
        className="min-h-0 flex-1 overflow-y-auto outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        {children}
      </div>
    </section>
  );
}
