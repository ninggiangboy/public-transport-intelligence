import { useQuery } from '@tanstack/react-query';
import { MapPin, Search } from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState } from 'react';

import { RouteBadge } from '@/components/RouteBadge';
import type { RouteItem, StopItem } from '@/features/map/model';
import { SEARCH_LIMIT, stopSearchQuery } from '@/features/map/queries';
import { mapCopy } from '@/i18n/map';
import { cn } from '@/lib/utils';

// "Search the map" (screens/live-map §4): routes from the cached E-01 and stops from E-06 `q`, like the search of the
// sidebar, but a route joins the filter and a stop moves the camera to it. "/" focuses it.

const copy = mapCopy.map.controls;
const STOP_DEBOUNCE_MS = 250;
const STOP_MIN_CHARS = 2;
const MAX_ROUTES = 6;

type Option = { kind: 'route'; route: RouteItem } | { kind: 'stop'; stop: StopItem };

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebounced(value);
    }, delayMs);
    return () => {
      clearTimeout(timer);
    };
  }, [value, delayMs]);
  return debounced;
}

interface MapSearchProps {
  routes: readonly RouteItem[];
  onRoute: (route: RouteItem) => void;
  onStop: (stop: StopItem) => void;
  placeholder: string;
  /** Mobile: a taller field (48 px). */
  large?: boolean;
}

export function MapSearch({ routes, onRoute, onStop, placeholder, large = false }: MapSearchProps) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const listId = useId();
  const q = query.trim().toLowerCase();
  const stopQuery = useDebounced(query.trim(), STOP_DEBOUNCE_MS);
  const stops = useQuery({
    ...stopSearchQuery(stopQuery),
    enabled: open && stopQuery.length >= STOP_MIN_CHARS,
    placeholderData: undefined,
  });

  const options = useMemo<Option[]>(() => {
    if (!q) return [];
    const matching = routes
      .filter((route) =>
        [route.shortName, route.longName, route.displayName].some((text) => text?.toLowerCase().includes(q)),
      )
      .slice(0, MAX_ROUTES)
      .map((route): Option => ({ kind: 'route', route }));
    const stopOptions = (stops.data?.data.items ?? [])
      .slice(0, SEARCH_LIMIT)
      .map((stop): Option => ({ kind: 'stop', stop }));
    return [...matching, ...stopOptions];
  }, [q, routes, stops.data]);

  // "/" anywhere outside a text field focuses the search (DOC-34 §4.3).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (event.key !== '/' || target?.closest('input, textarea, [contenteditable="true"]')) return;
      event.preventDefault();
      input.current?.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, []);

  const choose = (option: Option) => {
    if (option.kind === 'route') onRoute(option.route);
    else onStop(option.stop);
    setQuery('');
    setOpen(false);
  };

  const expanded = open && q.length > 0;
  const optionId = (index: number) => `${listId}-${index}`;
  return (
    <div className="relative">
      <label htmlFor={`${listId}-input`} className="sr-only">
        {copy.search}
      </label>
      <Search
        className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden="true"
      />
      <input
        ref={input}
        id={`${listId}-input`}
        type="search"
        role="combobox"
        aria-expanded={expanded}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={expanded && options.length > 0 ? optionId(active) : undefined}
        placeholder={placeholder}
        autoComplete="off"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setActive(0);
          setOpen(true);
        }}
        onFocus={() => {
          setOpen(true);
        }}
        onBlur={() => {
          setOpen(false);
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setActive((index) => Math.min(options.length - 1, index + 1));
          } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            setActive((index) => Math.max(0, index - 1));
          } else if (event.key === 'Enter') {
            const option = options[active];
            if (option) {
              event.preventDefault();
              choose(option);
            }
          } else if (event.key === 'Escape') {
            setOpen(false);
          }
        }}
        className={cn(
          'w-full rounded-[10px] border border-input bg-card pr-8 pl-9 text-sm outline-none placeholder:text-subtle-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/25',
          large ? 'h-12 rounded-[14px] text-[15px] shadow-md' : 'h-[38px]',
        )}
      />
      {large ? null : (
        <kbd className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 rounded border border-border bg-muted px-1.5 font-mono text-[11px] text-muted-foreground">
          {copy.searchKey}
        </kbd>
      )}
      {expanded ? (
        <ul
          id={listId}
          role="listbox"
          aria-label={copy.search}
          className="absolute inset-x-0 top-full z-20 mt-1 max-h-80 overflow-y-auto rounded-lg border border-border bg-popover p-1 shadow-lg"
        >
          {options.length === 0 ? (
            <li role="presentation" className="px-2 py-2.5 text-sm text-muted-foreground">
              {stops.isFetching ? '' : copy.searchEmpty}
            </li>
          ) : (
            options.map((option, index) => (
              <li
                key={option.kind === 'route' ? `r-${option.route.routeId}` : `s-${option.stop.stopId}`}
                id={optionId(index)}
                role="option"
                aria-selected={index === active}
                // Keeps focus in the field, so that blur does not close the list before the click lands.
                onMouseDown={(event) => {
                  event.preventDefault();
                }}
                onClick={() => {
                  choose(option);
                }}
                className={cn(
                  'flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm',
                  index === active && 'bg-muted',
                )}
              >
                {option.kind === 'route' ? (
                  <>
                    <RouteBadge
                      routeId={option.route.routeId}
                      displayName={option.route.displayName}
                      {...(option.route.color ? { color: option.route.color } : {})}
                      {...(option.route.textColor ? { textColor: option.route.textColor } : {})}
                      size="sm"
                    />
                    <span className="truncate">{option.route.longName ?? option.route.displayName}</span>
                  </>
                ) : (
                  <>
                    <MapPin className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <span className="truncate">{option.stop.name}</span>
                  </>
                )}
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  );
}
