import type { components } from '@/api/generated/schema';
import { useFreshness } from '@/app/freshness';
import { presetOf, type DayRange } from '@/features/scorecard/model';
import { scorecardCopy } from '@/i18n/scorecard';
import { useBusinessClock } from '@/lib/business-clock';
import { addDays, formatDate, zonedDate } from '@/lib/time';

type Direction = components['schemas']['DirectionResponse'];

const copy = scorecardCopy.scorecard;

/**
 * Yesterday by the business clock (DOC-34 §8) and the agency's zone. Scores end yesterday, so the page waits for
 * /system/freshness rather than guess from the wall clock; `ready` turns true with it.
 */
export function useServiceDays() {
  const clock = useBusinessClock();
  const { data: freshness } = useFreshness();
  const timezone = freshness?.activeFeed?.timezone ?? clock.timezone;
  const today = freshness ? zonedDate(freshness.businessNow, timezone) : undefined;
  return { ready: today !== undefined, yesterday: today ? addDays(today, -1) : '1970-01-01', timezone };
}

/** "Sep 22, 2026 – Sep 28, 2026" for service dates, which have no zone. */
export function formatDays(range: DayRange): string {
  return `${formatDate(`${range.from}T12:00:00Z`, 'UTC')} – ${formatDate(`${range.to}T12:00:00Z`, 'UTC')}`;
}

/** "vs previous week" for 7 days ending yesterday, "vs previous month" for 31, else "vs previous period". */
export function vsPrevious(range: DayRange, yesterday: string): string {
  return copy.kpi.vsPrevious[presetOf(range, yesterday) ?? 'other'];
}

/** A direction's name: the feed's label, else its headsign, else "Direction 0". */
export function directionName(direction: Direction | undefined, directionId: number): string {
  return direction?.label ?? direction?.headsign ?? copy.direction.fallback(directionId);
}

const FLAT_POINTS = 0.05;

/** The change of a percentage in points for a KpiCard: "1.3 pts", up or down, `good` the better way. */
export function pointsDelta(now: number | undefined, before: number | undefined, good: 'up' | 'down', caption: string) {
  if (now === undefined || before === undefined) return undefined;
  const change = now - before;
  return {
    value: copy.kpi.points(Math.abs(change).toFixed(1)),
    direction: Math.abs(change) < FLAT_POINTS ? ('flat' as const) : change > 0 ? ('up' as const) : ('down' as const),
    good,
    caption,
  };
}

/** The relative change of a count for a KpiCard: "2%", more is better. */
export function countDelta(now: number, before: number, caption: string, format: (ratio: number) => string) {
  if (before === 0) return undefined;
  const ratio = (now - before) / before;
  return {
    value: format(Math.abs(ratio)),
    direction: Math.abs(ratio) < 0.005 ? ('flat' as const) : ratio > 0 ? ('up' as const) : ('down' as const),
    good: 'up' as const,
    caption,
  };
}
