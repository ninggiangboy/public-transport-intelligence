import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Crosshair, MapPin } from 'lucide-react';
import { useEffect } from 'react';

import { Callout } from '@/components/Callout';
import { DispatchSuggestionCard } from '@/components/DispatchSuggestionCard';
import { LineStrip } from '@/components/LineStrip';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { ToneBadge } from '@/components/ToneBadge';
import { Button } from '@/components/ui/button';
import { CameraBlock, EstimateRow } from '@/features/map/components/CameraBlock';
import { Metric, Metrics, PanelFooter, PanelNotice, PanelSection } from '@/features/map/components/panel-parts';
import {
  DELAY_TONE,
  directionName,
  isFaded,
  occupancyLabel,
  orderedStops,
  routeName,
  tripProgress,
  vehicleHeadline,
  type LiveVehicle,
  type RouteItem,
} from '@/features/map/model';
import { bunchingDetailQuery, liveVehiclesQuery, routeDetailQuery, routeStopsQuery } from '@/features/map/queries';
import { useDispatchFeedback } from '@/features/map/use-dispatch-feedback';
// eslint-disable-next-line import-x/no-restricted-paths -- the 3D view's type-only contract, read by the map's panel (DR-111).
import type { CameraMode } from '@/features/map3d/api';
import { mapCopy } from '@/i18n/map';
import { map3dCopy } from '@/i18n/map3d';
import { useBusinessClock } from '@/lib/business-clock';
import { delayClass } from '@/lib/delay';
import { formatDuration, formatEta, formatPassengerDelay, formatSpeed } from '@/lib/format';
import { formatTime } from '@/lib/time';
import { useAxisNow, useRelative } from '@/lib/use-now';

const copy = mapCopy.map.vehicle;

/** The 3D view's camera state, handed to the panel when `view=3d` (screens/live-map §4.3). */
export interface View3d {
  mode: CameraMode;
  onMode: (mode: CameraMode) => void;
  fixAgeSeconds: number | undefined;
}

interface VehiclePanelProps {
  vehicleId: string;
  routeIds: readonly string[];
  routes: ReadonlyMap<string, RouteItem>;
  staff: boolean;
  operator: boolean;
  following: boolean;
  onFollow: (follow: boolean) => void;
  view3d?: View3d;
}

/** The selected bus (screens/live-map §4.1 `VehiclePanel`). */
export function VehiclePanel({
  vehicleId,
  routeIds,
  routes,
  staff,
  operator,
  following,
  onFollow,
  view3d,
}: VehiclePanelProps) {
  const queryClient = useQueryClient();
  const live = liveVehiclesQuery(routeIds);
  const vehicles = useQuery({
    ...live,
    select: (snapshot) => {
      const items = snapshot.data.items;
      const vehicle = items.find((item) => item.vehicleId === vehicleId);
      const partner = vehicle?.bunching
        ? items.find((item) => item.vehicleId === vehicle.bunching?.partnerVehicleId)
        : undefined;
      return { vehicle, partnerLabel: partner?.label ?? vehicle?.bunching?.partnerVehicleId };
    },
  });

  // `vehicles.batch` has no fresh delay: refetch the snapshot when a bus is opened (screens/live-map §5).
  const liveKey = JSON.stringify(live.queryKey);
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: JSON.parse(liveKey) as unknown[], exact: true });
  }, [queryClient, liveKey, vehicleId]);

  const vehicle = vehicles.data?.vehicle;
  if (!vehicle) {
    if (vehicles.isPending) return <PanelSkeleton variant="detail" />;
    return <PanelNotice>{mapCopy.map.panel.gone.vehicle}</PanelNotice>;
  }
  return (
    <VehicleDetail
      vehicle={vehicle}
      partnerLabel={vehicles.data?.partnerLabel}
      routes={routes}
      staff={staff}
      operator={operator}
      following={following}
      onFollow={onFollow}
      view3d={view3d}
    />
  );
}

function VehicleDetail({
  vehicle,
  partnerLabel,
  routes,
  staff,
  operator,
  following,
  onFollow,
  view3d,
}: Omit<VehiclePanelProps, 'vehicleId' | 'routeIds'> & { vehicle: LiveVehicle; partnerLabel?: string }) {
  const clock = useBusinessClock();
  const now = useAxisNow(vehicle.eventTimestamp, 'event');
  const updated = useRelative(vehicle.eventTimestamp, 'event');
  const route = routes.get(vehicle.routeId);
  const detail = useQuery(routeDetailQuery(vehicle.routeId));
  const stopsOfRoute = useQuery(routeStopsQuery(vehicle.routeId));
  const direction = detail.data?.data.directions.find((d) => d.directionId === vehicle.directionId);
  const stops = orderedStops(direction);
  const progress = tripProgress(vehicle, stops);
  const label = vehicle.label ?? vehicle.vehicleId;
  // Route types 0, 1 and 2 (tram, subway, rail) are drawn and named as a train (DR-111).
  const vehicleName = map3dCopy.map3d.vehicle(route !== undefined && route.routeType <= 2, label);
  const cls = delayClass(vehicle.delaySeconds);
  const occupancy = occupancyLabel(vehicle.occupancyStatus);
  const overlay = staff ? vehicle.bunching : undefined;
  const current = stops.find((stop) => stop.stopId === vehicle.stopId);

  const transfers = (stopId: string) => {
    const others = stopsOfRoute.data?.data.items
      .find((stop) => stop.stopId === stopId)
      ?.routeIds.filter((id) => id !== vehicle.routeId);
    return others && others.length > 0
      ? copy.transfer(others.map((id) => routeName(id, routes)).join(', '))
      : undefined;
  };

  return (
    <>
      <div className="flex items-start gap-3.5 px-[18px] pt-3.5 pb-4">
        <RouteBadge
          routeId={vehicle.routeId}
          displayName={routeName(vehicle.routeId, routes)}
          {...(route?.color ? { color: route.color } : {})}
          {...(route?.textColor ? { textColor: route.textColor } : {})}
          size="xl"
        />
        <div className="min-w-0">
          <h2 className="text-[17px] leading-tight font-semibold tracking-[-0.022em]">
            {vehicleHeadline(vehicle, route)}
          </h2>
          <p className="mt-1 text-[12.5px] text-muted-foreground">
            {copy.subtitle(label, directionName(direction, vehicle.directionId), vehicle.tripId)}
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <ToneBadge
              tone={DELAY_TONE[cls]}
              label={
                vehicle.delaySeconds === undefined
                  ? mapCopy.map.legend.delay.unknown
                  : formatPassengerDelay(vehicle.delaySeconds)
              }
            />
            {overlay ? <ToneBadge tone="bunching" label={copy.bunching} /> : null}
          </div>
          {isFaded(vehicle, now) ? <p className="mt-2 text-xs text-tone-warning-fg">{copy.lastSeen(updated)}</p> : null}
        </div>
      </div>
      <Metrics>
        <Metric
          label={copy.speed}
          value={vehicle.speedMps === undefined ? mapCopy.map.none : formatSpeed(vehicle.speedMps)}
        />
        <Metric
          label={copy.nextStop}
          value={
            vehicle.stopArrivalAt ? formatEta(vehicle.stopArrivalAt, clock.now(), clock.timezone) : mapCopy.map.none
          }
        />
        {occupancy ? (
          <Metric label={copy.occupancy} value={<span className="text-[13.5px]">{occupancy}</span>} />
        ) : null}
      </Metrics>
      {view3d?.fixAgeSeconds !== undefined ? <EstimateRow fixAgeSeconds={view3d.fixAgeSeconds} /> : null}
      {view3d ? <CameraBlock mode={view3d.mode} onMode={view3d.onMode} vehicle={vehicleName} /> : null}
      <PanelSection
        title={copy.tripProgress}
        aside={
          <span className="text-xs text-muted-foreground tabular-nums">{mapCopy.map.summary.updated(updated)}</span>
        }
      >
        {detail.isPending ? (
          <PanelSkeleton variant="list" rows={4} />
        ) : progress.stops.length > 0 ? (
          <LineStrip
            {...(route?.color ? { color: route.color } : {})}
            stops={progress.stops.map((entry) => ({
              id: entry.stop.stopId,
              name: entry.stop.name,
              state: entry.state,
              major: entry.terminus,
              meta:
                [entry.terminus ? copy.terminus : undefined, transfers(entry.stop.stopId)]
                  .filter(Boolean)
                  .join(' · ') || undefined,
              eta:
                entry.next && vehicle.stopArrivalAt ? (
                  <span className="flex flex-col items-end">
                    <span>
                      {copy.arriving(formatTime(vehicle.stopArrivalAt, { timeZone: clock.timezone, showZone: false }))}
                    </span>
                    {vehicle.delaySeconds === undefined ? null : (
                      <span className="text-[11.5px] font-medium text-muted-foreground">
                        {formatPassengerDelay(vehicle.delaySeconds)}
                      </span>
                    )}
                  </span>
                ) : undefined,
            }))}
            {...(progress.markerAfter ? { marker: { atStopId: progress.markerAfter, label: copy.now(label) } } : {})}
          />
        ) : current ? (
          <p className="text-sm">{current.name}</p>
        ) : null}
      </PanelSection>
      {overlay ? (
        <PanelSection>
          <div className="flex flex-col gap-2.5">
            <Callout tone="bunching">
              {(overlay.role === 'LEADER' ? copy.behind : copy.ahead)(
                partnerLabel ?? overlay.partnerVehicleId,
                formatDuration(overlay.gapSeconds * 1000),
                formatDuration(overlay.headwaySeconds * 1000),
              )}
            </Callout>
            <PairSuggestion episodeId={overlay.episodeId} operator={operator} />
          </div>
        </PanelSection>
      ) : null}
      <PanelFooter>
        {view3d ? null : (
          <Button
            variant={following ? 'default' : 'outline'}
            size="sm"
            aria-pressed={following}
            onClick={() => {
              onFollow(!following);
            }}
          >
            <Crosshair aria-hidden="true" />
            {following ? copy.stopFollowing : copy.follow}
          </Button>
        )}
        <Button variant="outline" size="sm" asChild>
          <Link to="/stops/$stopId" params={{ stopId: vehicle.stopId }}>
            <MapPin aria-hidden="true" />
            {copy.stopDetails}
          </Link>
        </Button>
      </PanelFooter>
    </>
  );
}

/** The suggestion of the pair the bus is in, with Accept / Dismiss for operators. */
export function PairSuggestion({ episodeId, operator }: { episodeId: string; operator: boolean }) {
  const episode = useQuery(bunchingDetailQuery(episodeId));
  const feedback = useDispatchFeedback(episodeId);
  const suggestion = episode.data?.data.suggestion;
  if (episode.isPending) return null;
  if (!suggestion) return <p className="text-sm text-muted-foreground">{mapCopy.map.bunching.noSuggestion}</p>;
  return (
    <DispatchSuggestionCard
      suggestion={suggestion}
      busy={feedback.isPending}
      {...(operator
        ? {
            onFeedback: (value: 'accepted' | 'ignored') => {
              feedback.mutate({ suggestionId: suggestion.id, feedback: value });
            },
          }
        : {})}
    />
  );
}
