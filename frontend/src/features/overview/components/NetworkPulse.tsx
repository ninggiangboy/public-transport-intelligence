import { useQueries } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';

import type { components } from '@/api/generated/schema';
import { RouteBadge } from '@/components/RouteBadge';
import { routeDetailQuery } from '@/features/overview/queries';
import { en } from '@/i18n/en';
import { parseHexColor, toCssRgb } from '@/lib/color';
import { delayClass, type DelayClass } from '@/lib/delay';
import { cn } from '@/lib/utils';

type LiveVehicle = components['schemas']['LiveVehicleResponse'];
type RouteItem = components['schemas']['RouteItemResponse'];

const DOT: Record<DelayClass, string> = {
  early: 'bg-delay-early',
  'on-time': 'bg-delay-on-time',
  late: 'bg-delay-late',
  'very-late': 'bg-delay-very-late',
  unknown: 'bg-delay-unknown',
};

const LEGEND = [
  { key: 'on-time', className: 'bg-delay-on-time' },
  { key: 'late', className: 'bg-delay-late' },
  { key: 'very-late', className: 'bg-delay-very-late' },
  { key: 'bunching', className: 'bg-card ring-2 ring-bunching' },
] as const;

interface NetworkPulseProps {
  routeIds: string[];
  vehicles: readonly LiveVehicle[];
  routes: Map<string, RouteItem>;
  /** Stops of open disruptions, by route (from the alert bodies). */
  disruptedStops: Map<string, string[]>;
}

/** Routes without a feed colour. */
const FALLBACK_RAIL = 'var(--primary)';

const clamp = (value: number) => Math.min(1, Math.max(0, value));

/**
 * A diagram, not a map (screens/overview §4.2): each route is its direction-0 line from first to last stop, every
 * vehicle a dot at `currentStopSequence / stops`, coloured by delay class; bunched pairs ringed; disrupted stretches red.
 */
export function NetworkPulse({ routeIds, vehicles, routes, disruptedStops }: NetworkPulseProps) {
  const details = useQueries({ queries: routeIds.map((routeId) => routeDetailQuery(routeId)) });
  return (
    <div className="flex flex-col gap-3.5">
      <ul className="flex flex-col gap-3">
        {routeIds.map((routeId, index) => {
          const route = routes.get(routeId);
          const direction = details[index]?.data?.data.directions.find((d) => d.directionId === 0);
          const stops = direction?.stops ?? [];
          const count = Math.max(stops.length, 1);
          const onRoute = vehicles.filter((vehicle) => vehicle.routeId === routeId);
          const affected = disruptedStops.get(routeId) ?? [];
          const positions = stops.flatMap((stop, at) =>
            affected.includes(stop.stopId) ? [at / Math.max(count - 1, 1)] : [],
          );
          const parsed = parseHexColor(route?.color);
          const rail = parsed ? toCssRgb(parsed) : FALLBACK_RAIL;
          return (
            <li key={routeId}>
              <Link
                to="/map"
                search={{ route: routeId } as never}
                aria-label={en.overview.pulse.route(route?.displayName ?? routeId, onRoute.length)}
                className="grid grid-cols-[44px_minmax(0,1fr)] items-center gap-3 rounded-md px-1 py-1 hover:bg-surface"
              >
                <RouteBadge
                  routeId={routeId}
                  displayName={route?.displayName ?? routeId}
                  color={route?.color}
                  textColor={route?.textColor}
                />
                <div className="min-w-0" aria-hidden="true">
                  <div className="relative h-4">
                    <span
                      className="absolute inset-x-0 top-1/2 h-1 -translate-y-1/2 rounded-full"
                      style={{ backgroundColor: rail }}
                    />
                    {positions.length > 0 ? (
                      <span
                        className="absolute top-1/2 h-1 -translate-y-1/2 rounded-full bg-delay-very-late"
                        style={{
                          left: `${Math.min(...positions) * 100}%`,
                          right: `${(1 - Math.max(...positions)) * 100}%`,
                        }}
                      />
                    ) : null}
                    {onRoute.map((vehicle) => (
                      <span
                        key={vehicle.vehicleId}
                        className={cn(
                          'absolute top-1/2 size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-card',
                          DOT[delayClass(vehicle.delaySeconds)],
                          vehicle.bunching && 'size-3 ring-bunching',
                        )}
                        style={{ left: `${clamp(vehicle.currentStopSequence / count) * 100}%` }}
                      />
                    ))}
                  </div>
                  {stops.length > 0 ? (
                    <div className="mt-1 flex justify-between gap-3 text-[11px] text-muted-foreground">
                      <span className="truncate">{stops[0]?.name}</span>
                      <span className="truncate text-right">{stops[stops.length - 1]?.name}</span>
                    </div>
                  ) : null}
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        {LEGEND.map((entry) => (
          <li key={entry.key} className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-2.5 rounded-full', entry.className)} />
            {en.overview.pulse.legend[entry.key]}
          </li>
        ))}
      </ul>
    </div>
  );
}
