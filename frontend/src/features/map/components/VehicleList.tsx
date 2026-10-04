import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useCallback, useState } from 'react';

import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RelativeTime } from '@/components/RelativeTime';
import { RouteBadge } from '@/components/RouteBadge';
import { ToneBadge } from '@/components/ToneBadge';
import {
  DELAY_TONE,
  groupByRoute,
  occupancyLabel,
  routeName,
  vehicleHeadline,
  visibleVehicles,
  type LiveVehicle,
  type RouteItem,
} from '@/features/map/model';
import { liveVehiclesQuery } from '@/features/map/queries';
import { mapCopy } from '@/i18n/map';
import { useBusinessClock } from '@/lib/business-clock';
import { delayClass } from '@/lib/delay';
import { formatEta, formatPassengerDelay } from '@/lib/format';

// `view=list` (screens/live-map §6, AC-9): the vehicles in a table grouped by route, with what the vehicle panel
// shows; groups fold, everything works from the keyboard.

const copy = mapCopy.map.list;

interface VehicleListProps {
  routeIds: readonly string[];
  routes: readonly RouteItem[];
  byId: ReadonlyMap<string, RouteItem>;
  onOpen: (vehicle: LiveVehicle) => void;
}

export function VehicleList({ routeIds, routes, byId, onOpen }: VehicleListProps) {
  const clock = useBusinessClock();
  const select = useCallback(
    (snapshot: { data: { items: LiveVehicle[] } }) => visibleVehicles(snapshot.data.items, clock.now()),
    [clock],
  );
  const query = useQuery({ ...liveVehiclesQuery(routeIds), select });
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());

  if (query.isPending) return <PanelSkeleton variant="table" />;
  if (query.isError && !query.data)
    return (
      <ErrorState
        error={query.error}
        variant="block"
        panel={mapCopy.map.summary.failed}
        onRetry={() => void query.refetch()}
      />
    );
  const vehicles = query.data;
  if (vehicles.length === 0) {
    const names = routeIds.map((id) => routeName(id, byId)).join(', ');
    return (
      <EmptyState
        title={routeIds.length > 0 ? mapCopy.map.empty.onRoute(names) : mapCopy.map.empty.title}
        description={mapCopy.map.empty.body}
      />
    );
  }
  const groups = groupByRoute(vehicles, routes);
  const toggle = (routeId: string) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(routeId)) next.delete(routeId);
      else next.add(routeId);
      return next;
    });
  };

  return (
    <div className="overflow-x-auto rounded-xl border border-border bg-card">
      <table className="w-full min-w-[720px] text-sm">
        <caption className="sr-only">{copy.caption}</caption>
        <thead className="border-b border-border bg-surface text-left text-xs text-muted-foreground">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">
              {copy.route}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {copy.bus}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {copy.destination}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {copy.nextStop}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {copy.delay}
            </th>
            <th scope="col" className="px-3 py-2 text-right font-medium">
              {copy.updated}
            </th>
          </tr>
        </thead>
        {groups.map((group) => {
          const route = byId.get(group.routeId);
          const name = routeName(group.routeId, byId);
          const open = !collapsed.has(group.routeId);
          return (
            <tbody key={group.routeId} className="border-b border-border last:border-b-0">
              <tr className="bg-muted/50">
                <th scope="rowgroup" colSpan={6} className="px-2 py-1 text-left">
                  <button
                    type="button"
                    aria-expanded={open}
                    onClick={() => {
                      toggle(group.routeId);
                    }}
                    className="inline-flex items-center gap-2 rounded-md px-1 py-1 text-[13px] font-semibold"
                  >
                    {open ? (
                      <ChevronDown className="size-4" aria-hidden="true" />
                    ) : (
                      <ChevronRight className="size-4" aria-hidden="true" />
                    )}
                    {copy.group(name, group.vehicles.length)}
                  </button>
                </th>
              </tr>
              {open
                ? group.vehicles.map((vehicle) => {
                    const occupancy = occupancyLabel(vehicle.occupancyStatus);
                    return (
                      <tr key={vehicle.vehicleId} className="border-t border-border hover:bg-surface">
                        <td className="px-3 py-2">
                          <RouteBadge
                            routeId={vehicle.routeId}
                            displayName={name}
                            {...(route?.color ? { color: route.color } : {})}
                            {...(route?.textColor ? { textColor: route.textColor } : {})}
                            size="sm"
                          />
                        </td>
                        <th scope="row" className="px-3 py-2 text-left font-normal">
                          <button
                            type="button"
                            className="font-mono font-medium text-primary underline-offset-4 hover:underline"
                            onClick={() => {
                              onOpen(vehicle);
                            }}
                          >
                            {vehicle.label ?? vehicle.vehicleId}
                          </button>
                        </th>
                        <td className="px-3 py-2">
                          {vehicleHeadline(vehicle, route)}
                          {occupancy ? <span className="block text-xs text-muted-foreground">{occupancy}</span> : null}
                        </td>
                        <td className="px-3 py-2 tabular-nums">
                          {vehicle.stopArrivalAt
                            ? formatEta(vehicle.stopArrivalAt, clock.now(), clock.timezone)
                            : mapCopy.map.none}
                        </td>
                        <td className="px-3 py-2">
                          <ToneBadge
                            tone={DELAY_TONE[delayClass(vehicle.delaySeconds)]}
                            size="sm"
                            label={
                              vehicle.delaySeconds === undefined
                                ? mapCopy.map.legend.delay.unknown
                                : formatPassengerDelay(vehicle.delaySeconds)
                            }
                          />
                        </td>
                        <td className="px-3 py-2 text-right text-muted-foreground tabular-nums">
                          <RelativeTime at={vehicle.eventTimestamp} axis="event" />
                        </td>
                      </tr>
                    );
                  })
                : null}
            </tbody>
          );
        })}
      </table>
    </div>
  );
}
