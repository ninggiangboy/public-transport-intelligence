import { AlertTriangle, ChevronRight, X } from 'lucide-react';
import { useState } from 'react';

import { RouteBadge } from '@/components/RouteBadge';
import { RouteSelect } from '@/components/RouteSelect';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { MapSearch } from '@/features/map/components/MapSearch';
import { routeName, type RouteItem, type StopItem } from '@/features/map/model';
import { COLOUR_MODES, MAX_ROUTES, type ColourMode } from '@/features/map/search';
import type { components } from '@/api/generated/schema';
import { mapCopy } from '@/i18n/map';
import { useBusinessClock } from '@/lib/business-clock';
import { formatTime } from '@/lib/time';
import { cn } from '@/lib/utils';

// The panel at the top left of the map (screens/live-map §4): search, route chips, colour mode, disruptions.

type Disruption = components['schemas']['DisruptionResponse'];

const copy = mapCopy.map.controls;

interface RouteChipsProps {
  routes: readonly RouteItem[];
  byId: ReadonlyMap<string, RouteItem>;
  selected: readonly string[];
  /** Routes whose E-02 failed: their chip carries an error icon. */
  failed: ReadonlySet<string>;
  onChange: (routeIds: string[]) => void;
  className?: string;
}

/** A chip per selected route with a remove button, then "+ Route" (≤ 20). */
export function RouteChips({ routes, byId, selected, failed, onChange, className }: RouteChipsProps) {
  return (
    <div className={cn('flex flex-wrap items-center gap-1.5', className)}>
      {selected.map((routeId) => {
        const route = byId.get(routeId);
        const name = routeName(routeId, byId);
        return (
          <span
            key={routeId}
            className="inline-flex h-7 items-center gap-1 rounded-full border border-border-strong bg-card pr-1 pl-1 text-[12.5px] font-medium"
          >
            <RouteBadge
              routeId={routeId}
              displayName={name}
              {...(route?.color ? { color: route.color } : {})}
              {...(route?.textColor ? { textColor: route.textColor } : {})}
              size="sm"
            />
            {failed.has(routeId) ? (
              <span title={copy.routeError(name)} role="img" aria-label={copy.routeError(name)}>
                <AlertTriangle className="size-3.5 text-tone-danger-fg" aria-hidden="true" />
              </span>
            ) : null}
            <button
              type="button"
              aria-label={copy.removeRoute(name)}
              onClick={() => {
                onChange(selected.filter((id) => id !== routeId));
              }}
              className="grid size-5 place-items-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <X className="size-3" aria-hidden="true" />
            </button>
          </span>
        );
      })}
      <RouteSelect
        routes={[...routes]}
        value={[...selected]}
        onChange={onChange}
        max={MAX_ROUTES}
        triggerText={copy.addRoute}
      />
    </div>
  );
}

interface DisruptionChipProps {
  disruptions: readonly Disruption[];
  byId: ReadonlyMap<string, RouteItem>;
  onSelect: (disruption: Disruption) => void;
}

/** "{n} active disruptions" with the list in a popover; nothing when there are none. */
export function DisruptionChip({ disruptions, byId, onSelect }: DisruptionChipProps) {
  const clock = useBusinessClock();
  const [open, setOpen] = useState(false);
  if (disruptions.length === 0) return null;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="inline-flex h-8 w-full items-center gap-2 rounded-lg border border-tone-danger-border bg-tone-danger-bg px-2.5 text-[12.5px] font-medium text-tone-danger-fg"
        >
          <AlertTriangle className="size-4" aria-hidden="true" />
          <span className="flex-1 text-left">{copy.disruptions(disruptions.length)}</span>
          <ChevronRight className="size-4" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent aria-label={copy.disruptionList} className="w-80 p-1" align="start">
        <ul className="flex max-h-72 flex-col overflow-y-auto">
          {disruptions.map((disruption) => {
            const route = byId.get(disruption.routeId);
            const name = routeName(disruption.routeId, byId);
            return (
              <li key={disruption.id}>
                <button
                  type="button"
                  className="flex w-full items-center gap-2.5 rounded-md px-2 py-2 text-left text-sm hover:bg-muted"
                  onClick={() => {
                    setOpen(false);
                    onSelect(disruption);
                  }}
                >
                  <RouteBadge
                    routeId={disruption.routeId}
                    displayName={name}
                    {...(route?.color ? { color: route.color } : {})}
                    {...(route?.textColor ? { textColor: route.textColor } : {})}
                    size="sm"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{mapCopy.map.disruption.title(name, '')}</span>
                    <span className="block text-xs text-muted-foreground">
                      {mapCopy.map.disruption.since(
                        formatTime(disruption.episodeStart, { timeZone: clock.timezone, showZone: false }),
                      )}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}

interface ControlPanelProps {
  routes: readonly RouteItem[];
  byId: ReadonlyMap<string, RouteItem>;
  selected: readonly string[];
  failed: ReadonlySet<string>;
  onRoutesChange: (routeIds: string[]) => void;
  onRoute: (route: RouteItem) => void;
  onStop: (stop: StopItem) => void;
  mode: ColourMode;
  onModeChange: (mode: ColourMode) => void;
  disruptions: readonly Disruption[];
  onDisruption: (disruption: Disruption) => void;
}

/** The desktop panel, 340 px wide (DOC-35 §5.8 `MapFloatPanel`). */
export function ControlPanel(props: ControlPanelProps) {
  return (
    <section
      aria-label={copy.panel}
      className="absolute top-4 left-4 z-10 flex w-[340px] flex-col gap-2.5 rounded-xl border border-border bg-card/94 p-3 shadow-md backdrop-blur-[12px]"
    >
      <MapSearch routes={props.routes} onRoute={props.onRoute} onStop={props.onStop} placeholder={copy.search} />
      <RouteChips
        routes={props.routes}
        byId={props.byId}
        selected={props.selected}
        failed={props.failed}
        onChange={props.onRoutesChange}
      />
      {/* Tighter segments, so that label and control share one row of the 340 px panel (prototype `.pti-seg--sm`). */}
      <div className="flex items-center justify-between gap-2 [&_[role=radio]]:px-2.5">
        <span className="text-[12.5px] whitespace-nowrap text-muted-foreground">{copy.colourBy}</span>
        <SegmentedControl
          size="sm"
          label={copy.colourBy}
          value={props.mode}
          onChange={props.onModeChange}
          options={COLOUR_MODES.map((value) => ({ value, label: copy.colour[value] }))}
        />
      </div>
      <DisruptionChip disruptions={props.disruptions} byId={props.byId} onSelect={props.onDisruption} />
    </section>
  );
}

/** A plain chip of the mobile bar ("Nearby", "Saved"), solid while on (prototype `.pti-chip`). */
export function MapChip({
  active,
  children,
  onClick,
}: {
  active: boolean;
  children: React.ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'inline-flex h-[34px] shrink-0 items-center rounded-full border px-3 text-[13px] font-medium whitespace-nowrap shadow-sm',
        active ? 'border-foreground bg-foreground text-card' : 'border-border bg-card text-foreground-2 hover:bg-muted',
      )}
    >
      {children}
    </button>
  );
}
