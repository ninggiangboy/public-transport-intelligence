import { WHITE, parseHexColor, pickRouteTextColor, toCssRgb } from '@/lib/color';
import { cn } from '@/lib/utils';

interface RouteBadgeProps {
  routeId: string;
  displayName: string;
  /** GTFS `route_color`, hex without "#". */
  color?: string;
  /** GTFS `route_text_color`. */
  textColor?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl';
}

const SIZES = {
  sm: 'h-[18px] min-w-[18px] px-1 text-[11px] rounded-sm',
  md: 'h-[22px] min-w-[22px] px-1.5 text-xs rounded-sm',
  lg: 'h-8 min-w-8 px-2 text-panel rounded-sm',
  xl: 'h-11 min-w-11 px-2.5 text-xl rounded-[11px]',
} as const;

const CHART_COLORS = 8;

function hash(text: string): number {
  let h = 0;
  for (const char of text) h = (h * 31 + (char.codePointAt(0) ?? 0)) >>> 0;
  return h;
}

/**
 * Route shield (DOC-35 §2.1, §5.1): a rounded square in the route colour with the route number in bold. The text
 * keeps 4.5:1 against the background (DS-05). Without a feed colour the background is a categorical chart colour
 * picked by a hash of the route id, darkened to 60% so white text reads on every one of them in both themes.
 */
export function RouteBadge({ routeId, displayName, color, textColor, size = 'md' }: RouteBadgeProps) {
  const background = parseHexColor(color);
  const className = cn(
    'inline-flex shrink-0 items-center justify-center leading-none font-bold tabular-nums',
    SIZES[size],
  );
  if (!background) {
    const chart = (hash(routeId) % CHART_COLORS) + 1;
    return (
      <span
        className={className}
        style={{ backgroundColor: `color-mix(in srgb, var(--chart-${chart}) 60%, black)`, color: toCssRgb(WHITE) }}
      >
        {displayName}
      </span>
    );
  }
  return (
    <span
      className={className}
      style={{ backgroundColor: toCssRgb(background), color: toCssRgb(pickRouteTextColor(background, textColor)) }}
    >
      {displayName}
    </span>
  );
}
