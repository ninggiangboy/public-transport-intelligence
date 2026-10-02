import { useId, useMemo, useState } from 'react';

import { FilterChip } from '@/components/FilterChip';
import { RouteBadge } from '@/components/RouteBadge';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { en } from '@/i18n/en';

/** The part of a route the select needs (the `RouteSummary` of E-01, without importing it from api/). */
export interface RouteOption {
  routeId: string;
  shortName?: string;
  longName?: string;
  routeType?: number;
  color?: string;
  textColor?: string;
}

interface RouteSelectProps {
  /** The routes to choose from; the caller loads them once per session (DOC-35 §5.3). */
  routes: RouteOption[];
  value: string[];
  onChange: (routeIds: string[]) => void;
  /** Default true. */
  multiple?: boolean;
  /** Most routes that can be picked at once (the API limit). Default 20. */
  max?: number;
  /** Only routes of these GTFS types are offered. */
  routeTypes?: number[];
  placeholder?: string;
}

function displayName(route: RouteOption): string {
  return route.shortName ?? route.longName ?? route.routeId;
}

/** Search and pick routes by `shortName` or `longName` (DOC-35 §5.3). */
export function RouteSelect({
  routes,
  value,
  onChange,
  multiple = true,
  max = 20,
  routeTypes,
  placeholder = en.route.select.none,
}: RouteSelectProps) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const searchId = useId();
  const baseId = useId();

  const available = useMemo(
    () =>
      routeTypes
        ? routes.filter((route) => route.routeType !== undefined && routeTypes.includes(route.routeType))
        : routes,
    [routes, routeTypes],
  );
  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return available;
    return available.filter((route) =>
      `${route.shortName ?? ''} ${route.longName ?? ''}`.toLowerCase().includes(needle),
    );
  }, [available, query]);

  const selected = available.filter((route) => value.includes(route.routeId));
  const atMax = multiple && value.length >= max;

  const toggle = (routeId: string, checked: boolean) => {
    if (!multiple) {
      onChange(checked ? [routeId] : []);
      setOpen(false);
      return;
    }
    onChange(checked ? [...value, routeId] : value.filter((id) => id !== routeId));
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery('');
      }}
    >
      <PopoverTrigger asChild>
        <FilterChip
          active={selected.length > 0}
          text={
            selected.length === 0
              ? placeholder
              : selected.length <= 3
                ? selected.map(displayName).join(', ')
                : en.route.select.selected(selected.length)
          }
          aria-label={en.route.select.label}
        />
      </PopoverTrigger>
      <PopoverContent aria-label={en.route.select.label} className="w-72">
        <label htmlFor={searchId} className="sr-only">
          {en.route.select.placeholder}
        </label>
        <Input
          id={searchId}
          type="search"
          placeholder={en.route.select.placeholder}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
          }}
        />
        {multiple ? <p className="px-2 pt-1.5 text-xs text-muted-foreground">{en.route.select.max(max)}</p> : null}
        <ul className="mt-1 flex max-h-64 flex-col overflow-y-auto">
          {matches.map((route, index) => {
            const checked = value.includes(route.routeId);
            const id = `${baseId}-${index}`;
            return (
              <li
                key={route.routeId}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 text-base hover:bg-muted"
              >
                <Checkbox
                  id={id}
                  checked={checked}
                  disabled={!checked && atMax}
                  onCheckedChange={(next) => {
                    toggle(route.routeId, next === true);
                  }}
                />
                <label htmlFor={id} className="flex min-w-0 flex-1 cursor-pointer items-center gap-2">
                  <RouteBadge
                    routeId={route.routeId}
                    displayName={displayName(route)}
                    {...(route.color ? { color: route.color } : {})}
                    {...(route.textColor ? { textColor: route.textColor } : {})}
                    size="sm"
                  />
                  <span className="truncate">{route.longName ?? ''}</span>
                </label>
              </li>
            );
          })}
          {matches.length === 0 ? (
            <li className="px-2 py-3 text-sm text-muted-foreground">{en.common.noMatches}</li>
          ) : null}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
