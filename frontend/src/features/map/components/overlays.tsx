import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronUp, Layers, List, LocateFixed, Minus, Plus } from 'lucide-react';
import { useCallback, type ReactNode } from 'react';

import { EmptyState } from '@/components/EmptyState';
import { FreshnessIndicator } from '@/components/FreshnessIndicator';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Button } from '@/components/ui/button';
import {
  legendRows,
  overlayCounts,
  OVERLAY_TOKEN,
  routeName,
  visibleVehicles,
  type LiveVehicle,
  type RouteItem,
} from '@/features/map/model';
import { liveVehiclesQuery } from '@/features/map/queries';
import { COLOUR_MODES, type ColourMode } from '@/features/map/search';
import { mapCopy } from '@/i18n/map';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCount } from '@/lib/format';
import { cn } from '@/lib/utils';

// The floating pieces over the map (screens/live-map §4): summary pill, legend, map controls, empty state.

const copy = mapCopy.map;

/** A panel floating over the map (DOC-35 §5.8 `MapFloatPanel`). */
export function MapFloatPanel({ className, children, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      className={cn('absolute rounded-xl border border-border bg-card/94 shadow-md backdrop-blur-[12px]', className)}
      {...props}
    >
      {children}
    </div>
  );
}

function newest(vehicles: readonly LiveVehicle[]): string | undefined {
  let latest: string | undefined;
  for (const vehicle of vehicles)
    if (latest === undefined || vehicle.eventTimestamp > latest) latest = vehicle.eventTimestamp;
  return latest;
}

/** "{n} vehicles · Updated {relative}" at the top of the map (screens/live-map §4, §7). */
export function SummaryPill({ routeIds, className }: { routeIds: readonly string[]; className?: string }) {
  const clock = useBusinessClock();
  const select = useCallback(
    (snapshot: { data: { items: LiveVehicle[] } }) => {
      const vehicles = visibleVehicles(snapshot.data.items, clock.now());
      return {
        count: vehicles.length,
        routes: new Set(vehicles.map((vehicle) => vehicle.routeId)).size,
        newest: newest(vehicles),
      };
    },
    [clock],
  );
  const query = useQuery({ ...liveVehiclesQuery(routeIds), select });
  const summary = query.data;
  // A new observer of the failed query refetches it; keep the error on show until a snapshot arrives.
  const failed = query.isError || (summary === undefined && query.errorUpdatedAt > 0);
  const retry = (
    <Button
      variant="ghost"
      size="sm"
      className="h-6 px-2"
      onClick={() => {
        void query.refetch();
      }}
    >
      {copy.summary.retry}
    </Button>
  );
  return (
    <MapFloatPanel
      className={cn('flex h-9 items-center gap-2.5 rounded-full px-3 text-[12.5px] whitespace-nowrap', className)}
    >
      {summary ? (
        <>
          <span className="font-medium text-foreground tabular-nums">
            {routeIds.length > 0
              ? copy.summary.vehiclesOnRoutes(summary.count, summary.routes)
              : copy.summary.vehicles(summary.count)}
          </span>
          <span className="h-3.5 w-px bg-border" aria-hidden="true" />
          {failed ? (
            <span className="inline-flex items-center gap-1 text-tone-danger-fg">
              {copy.summary.failed}
              {retry}
            </span>
          ) : (
            <FreshnessIndicator asOf={summary.newest} axis="event" staleAfterSeconds={90} />
          )}
        </>
      ) : failed ? (
        <span className="inline-flex items-center gap-1 text-tone-danger-fg" role="alert">
          {copy.summary.failed}
          {retry}
        </span>
      ) : (
        <span className="text-muted-foreground">{copy.summary.loading}</span>
      )}
    </MapFloatPanel>
  );
}

/** "No vehicles in service right now" over the map once the snapshot is empty (screens/live-map §7). */
export function EmptyVehicles({
  routeIds,
  routes,
}: {
  routeIds: readonly string[];
  routes: ReadonlyMap<string, RouteItem>;
}) {
  const clock = useBusinessClock();
  const select = useCallback(
    (snapshot: { data: { items: LiveVehicle[] } }) => visibleVehicles(snapshot.data.items, clock.now()).length,
    [clock],
  );
  const query = useQuery({ ...liveVehiclesQuery(routeIds), select });
  if (query.data !== 0 || query.isPlaceholderData) return null;
  const names = routeIds.map((id) => routeName(id, routes)).join(', ');
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-4">
      <MapFloatPanel className="pointer-events-auto static max-w-sm px-6 py-2">
        <EmptyState
          title={routeIds.length > 0 ? copy.empty.onRoute(names) : copy.empty.title}
          description={copy.empty.body}
        />
      </MapFloatPanel>
    </div>
  );
}

interface LegendProps {
  routeIds: readonly string[];
  mode: ColourMode;
  routes: ReadonlyMap<string, RouteItem>;
  staff: boolean;
  disruptions: number;
  open: boolean;
  onToggle: () => void;
  /** Mobile: the colour mode lives in the legend. */
  onModeChange?: (mode: ColourMode) => void;
  className?: string;
}

function Swatch({ colour, shape = 'dot' }: { colour: string; shape?: 'dot' | 'ring' | 'bar' | 'faded' | 'cluster' }) {
  const base = 'inline-block shrink-0';
  if (shape === 'ring')
    return (
      <span
        className={cn(base, 'h-2.5 w-3.5 rounded-md border-[1.75px] border-dashed')}
        style={{ borderColor: colour }}
      />
    );
  if (shape === 'bar')
    return <span className={cn(base, 'h-1.5 w-3.5 rounded-sm opacity-35')} style={{ background: colour }} />;
  if (shape === 'cluster') return <span className={cn(base, 'size-3 rounded-full bg-foreground ring-2 ring-card')} />;
  return (
    <span
      className={cn(
        base,
        'size-2.5 rounded-full border-2 border-card shadow-[0_0_0_1px_rgb(0_0_0/0.08)]',
        shape === 'faded' && 'opacity-40',
      )}
      style={{ background: colour }}
    />
  );
}

function Row({ swatch, label, count }: { swatch: ReactNode; label: string; count?: number }) {
  return (
    <tr className="text-[12.5px]">
      <th scope="row" className="py-[3px] text-left font-normal">
        <span className="inline-flex items-center gap-2">
          {swatch}
          {label}
        </span>
      </th>
      <td className="py-[3px] pl-4 text-right text-muted-foreground tabular-nums">
        {count === undefined ? '' : formatCount(count)}
      </td>
    </tr>
  );
}

/** The legend of DOC-35 §6.3: an HTML table of the layers with the vehicles drawn in each right now. */
export function Legend({
  routeIds,
  mode,
  routes,
  staff,
  disruptions,
  open,
  onToggle,
  onModeChange,
  className,
}: LegendProps) {
  const clock = useBusinessClock();
  const select = useCallback(
    (snapshot: { data: { items: LiveVehicle[] } }) => {
      const vehicles = visibleVehicles(snapshot.data.items, clock.now());
      return { rows: legendRows(vehicles, mode, routes), overlays: overlayCounts(vehicles, clock.now()) };
    },
    [clock, mode, routes],
  );
  const query = useQuery({ ...liveVehiclesQuery(routeIds), select });
  const rows = query.data?.rows ?? legendRows([], mode, routes);
  const overlays = query.data?.overlays ?? { bunching: 0, stale: 0 };
  return (
    <MapFloatPanel className={cn('w-[236px] overflow-hidden', className)}>
      <button
        type="button"
        aria-expanded={open}
        onClick={onToggle}
        className="flex w-full items-center justify-between px-3 py-2.5 text-[12.5px] font-semibold"
      >
        {copy.legend.title}
        {open ? (
          <ChevronDown className="size-4" aria-hidden="true" />
        ) : (
          <ChevronUp className="size-4" aria-hidden="true" />
        )}
      </button>
      {open ? (
        <div
          tabIndex={0}
          className="max-h-[50vh] overflow-y-auto px-3 pb-2.5 outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {onModeChange ? (
            <div className="mb-2">
              <SegmentedControl
                size="sm"
                label={copy.controls.colourBy}
                value={mode}
                onChange={onModeChange}
                options={COLOUR_MODES.map((value) => ({ value, label: copy.controls.colour[value] }))}
              />
            </div>
          ) : null}
          <table className="w-full">
            <caption className="sr-only">{copy.legend.title}</caption>
            <thead className="sr-only">
              <tr>
                <th scope="col">{copy.legend.layer}</th>
                <th scope="col">{copy.legend.count}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <Row key={row.key} swatch={<Swatch colour={row.colour} />} label={row.label} count={row.count} />
              ))}
            </tbody>
            <tbody className="border-t border-border">
              {staff ? (
                <Row
                  swatch={<Swatch colour={OVERLAY_TOKEN.bunching} shape="ring" />}
                  label={copy.legend.bunching}
                  count={overlays.bunching}
                />
              ) : null}
              <Row
                swatch={<Swatch colour={OVERLAY_TOKEN.disruption} shape="bar" />}
                label={copy.legend.disruption}
                count={disruptions}
              />
              <Row
                swatch={<Swatch colour={OVERLAY_TOKEN.stale} shape="faded" />}
                label={copy.legend.stale}
                count={overlays.stale}
              />
              <Row swatch={<Swatch colour="" shape="cluster" />} label={copy.legend.cluster} />
            </tbody>
          </table>
        </div>
      ) : null}
    </MapFloatPanel>
  );
}

interface MapControlsProps {
  onZoomIn: () => void;
  onZoomOut: () => void;
  onLocate: () => void;
  onToggleLegend: () => void;
  legendOpen: boolean;
  onListView: () => void;
  /** Touch targets of 44 px (screens/live-map §4). */
  touch: boolean;
  className?: string;
}

function ControlButton({
  label,
  onClick,
  touch,
  pressed,
  children,
}: {
  label: string;
  onClick: () => void;
  touch: boolean;
  pressed?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        'grid place-items-center border-b border-border bg-card text-foreground-2 last:border-b-0 hover:bg-muted',
        touch ? 'size-11' : 'size-[34px]',
      )}
    >
      {children}
    </button>
  );
}

/** Zoom, "Locate vehicles", legend and list view (DOC-35 §5.8 `MapControls`). */
export function MapControls({
  onZoomIn,
  onZoomOut,
  onLocate,
  onToggleLegend,
  legendOpen,
  onListView,
  touch,
  className,
}: MapControlsProps) {
  const icon = 'size-4';
  return (
    <div className={cn('absolute flex flex-col gap-2', className)}>
      <div className="flex flex-col overflow-hidden rounded-[10px] border border-border bg-card shadow-md">
        <ControlButton label={copy.controls.locate} onClick={onLocate} touch={touch}>
          <LocateFixed className={icon} aria-hidden="true" />
        </ControlButton>
        <ControlButton label={copy.controls.legend} onClick={onToggleLegend} touch={touch} pressed={legendOpen}>
          <Layers className={icon} aria-hidden="true" />
        </ControlButton>
        <ControlButton label={copy.controls.listView} onClick={onListView} touch={touch}>
          <List className={icon} aria-hidden="true" />
        </ControlButton>
      </div>
      <div className="flex flex-col overflow-hidden rounded-[10px] border border-border bg-card shadow-md">
        <ControlButton label={copy.controls.zoomIn} onClick={onZoomIn} touch={touch}>
          <Plus className={icon} aria-hidden="true" />
        </ControlButton>
        <ControlButton label={copy.controls.zoomOut} onClick={onZoomOut} touch={touch}>
          <Minus className={icon} aria-hidden="true" />
        </ControlButton>
      </div>
    </div>
  );
}
