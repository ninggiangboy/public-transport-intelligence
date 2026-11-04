import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { supportsWebGL2 } from '@/features/map/webgl';
import { mapCopy } from '@/i18n/map';
import { useMediaQuery } from '@/lib/browser';
import { cn } from '@/lib/utils';

// The "2D | 3D" switch of the live map (screens/live-map §3.1, §4, DR-111): desktop only, at the top right of the map
// next to the detail panel. It is not lazily loaded (it must be there before 3D is entered), so it keeps its own copy
// in src/i18n/map.ts and reads WebGL support from webgl.ts.

const copy = mapCopy.map.view3d;
const DESKTOP = '(min-width: 1024px)';

interface MapViewSwitchProps {
  /** Whether the map is showing 3D right now. */
  view3d: boolean;
  onView3d: (value: boolean) => void;
  className?: string;
}

export function MapViewSwitch({ view3d, onView3d, className }: MapViewSwitchProps) {
  const desktop = useMediaQuery(DESKTOP);
  const webgl = supportsWebGL2();
  // Below 1024 px there is no 3D at all, not even a disabled switch (screens/live-map §7).
  if (!desktop) return null;
  return (
    <div
      role="radiogroup"
      aria-label={copy.label}
      className={cn(
        'absolute inline-flex gap-0.5 rounded-md border border-border bg-card/94 p-0.5 shadow-md backdrop-blur-[12px]',
        className,
      )}
    >
      <ViewOption
        label={copy.two}
        selected={!view3d}
        onSelect={() => {
          onView3d(false);
        }}
      />
      {webgl ? (
        <ViewOption
          label={copy.three}
          selected={view3d}
          onSelect={() => {
            onView3d(true);
          }}
        />
      ) : (
        <Tooltip>
          <TooltipTrigger asChild>
            {/* A disabled button gets no pointer events, so the tooltip listens on this wrapper (like ReadOnlyBadge). */}
            <span tabIndex={0} className="inline-flex rounded-[7px]">
              <ViewOption label={copy.three} selected={view3d} disabled />
            </span>
          </TooltipTrigger>
          <TooltipContent>{copy.webgl}</TooltipContent>
        </Tooltip>
      )}
    </div>
  );
}

function ViewOption({
  label,
  selected,
  onSelect,
  disabled = false,
}: {
  label: string;
  selected: boolean;
  onSelect?: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={disabled}
      onClick={onSelect}
      className={cn(
        'h-7 rounded-[7px] px-3 text-label font-medium whitespace-nowrap text-muted-foreground',
        'disabled:cursor-not-allowed disabled:opacity-60',
        !disabled && 'hover:text-foreground',
        selected && 'bg-muted text-foreground shadow-xs',
      )}
    >
      {label}
    </button>
  );
}
