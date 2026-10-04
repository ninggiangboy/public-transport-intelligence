import { useQueries } from '@tanstack/react-query';

import type { components } from '@/api/generated/schema';
import { Card } from '@/components/Card';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { RouteBadge } from '@/components/RouteBadge';
import { routeDirection, type Arrival, type Confidence } from '@/features/stops/arrivals';
import { delayProfileQuery } from '@/features/stops/queries';
import { en } from '@/i18n/en';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDelaySeconds } from '@/lib/format';
import { formatHourOfDay, formatWeekdayLong, zonedWeekdayHour } from '@/lib/time';

type StopRoute = components['schemas']['StopRouteResponse'];

const MAX_ROUTES = 4;

function levelOf(confidence: string): Confidence {
  return confidence === 'HIGH' || confidence === 'MEDIUM' || confidence === 'LOW' ? confidence : 'NONE';
}

interface ReliabilityCardProps {
  stopId: string;
  routes: readonly StopRoute[];
  /** For the direction each route runs at this stop. */
  arrivals: readonly Arrival[];
}

/**
 * How late each route usually is here at this hour of the week (E-04, DOC-23 §7): "avg … · p90 …" per route, at most
 * four. Hidden when the profiles cannot be loaded (DOC-36 screens/stop-detail §7).
 */
export function ReliabilityCard({ stopId, routes, arrivals }: ReliabilityCardProps) {
  const clock = useBusinessClock();
  const { dayOfWeek, hourOfDay } = zonedWeekdayHour(clock.now(), clock.timezone);
  const shown = routes.slice(0, MAX_ROUTES);
  const profiles = useQueries({
    queries: shown.map((route) =>
      delayProfileQuery(route.routeId, {
        directionId: routeDirection(arrivals, route.routeId) ?? 0,
        dayOfWeek,
        hourOfDay,
      }),
    ),
  });

  const rows = shown.flatMap((route, index) => {
    const item = profiles[index]?.data?.data.items.find((entry) => entry.stopId === stopId);
    return item ? [{ route, item }] : [];
  });
  if (rows.length === 0) return null;

  return (
    <Card
      title={en.stops.reliability.title}
      meta={en.stops.reliability.when(formatWeekdayLong(dayOfWeek), formatHourOfDay(hourOfDay))}
    >
      <ul className="flex flex-col gap-3">
        {rows.map(({ route, item }) => {
          const level = levelOf(item.confidence);
          return (
            <li key={route.routeId} className="flex items-center gap-3 text-sm">
              <RouteBadge
                routeId={route.routeId}
                displayName={route.displayName}
                color={route.color}
                textColor={route.textColor}
              />
              <span className="min-w-0 flex-1 tabular-nums">
                {level === 'NONE' || item.avgDelaySeconds === undefined || item.p90DelaySeconds === undefined
                  ? en.stops.reliability.notEnough
                  : en.stops.reliability.stats(
                      formatDelaySeconds(item.avgDelaySeconds),
                      formatDelaySeconds(item.p90DelaySeconds),
                    )}
              </span>
              <ConfidenceChip level={level} sampleCount={item.sampleCount} />
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
