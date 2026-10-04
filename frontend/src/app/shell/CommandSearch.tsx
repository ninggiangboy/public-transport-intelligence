import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { MapPin } from 'lucide-react';
import { useEffect, useState } from 'react';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import type { NavItem } from '@/app/shell/nav-items';
import { RouteBadge } from '@/components/RouteBadge';
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/ui/command';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { en } from '@/i18n/en';

const STOP_DEBOUNCE_MS = 250;
const STOP_MIN_CHARS = 2;
const MAX_RESULTS = 8;
const HOUR_MS = 3_600_000;
/** A route opens the live map filtered to it: `/map?route=<id>` (DOC-34 §4.1). */
const MAP_PATH = '/map';

interface CommandSearchProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The pages this user may open. */
  pages: readonly NavItem[];
}

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

/** Ranks a match: exact first, then prefix, then substring; `undefined` when `text` does not contain `q`. */
function rank(q: string, ...texts: (string | undefined)[]): number | undefined {
  let best: number | undefined;
  for (const text of texts) {
    const value = text?.toLowerCase();
    if (!value) continue;
    const score = value === q ? 0 : value.startsWith(q) ? 1 : value.includes(q) ? 2 : undefined;
    if (score !== undefined && (best === undefined || score < best)) best = score;
  }
  return best;
}

/**
 * The search of the sidebar (`⌘K`): pages the user may open, routes from the cached E-01 and stops from E-06 `q`
 * (DOC-34 §4.1). A route opens the live map filtered to it; a stop opens its detail.
 */
export function CommandSearch({ open, onOpenChange, pages }: CommandSearchProps) {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  const stopQuery = useDebounced(query.trim(), STOP_DEBOUNCE_MS);
  const searchStops = open && stopQuery.length >= STOP_MIN_CHARS;

  const routes = useQuery({
    queryKey: keys.routes.list(),
    queryFn: () => read(api.GET('/api/v1/routes')),
    enabled: open,
    staleTime: HOUR_MS,
    refetchInterval: false,
  });
  const stops = useQuery({
    queryKey: keys.stops.search(stopQuery),
    queryFn: () => read(api.GET('/api/v1/stops', { params: { query: { q: stopQuery, limit: MAX_RESULTS } } })),
    enabled: searchStops,
    refetchInterval: false,
    // A new query must not show the stops of the previous one.
    placeholderData: undefined,
  });

  const matchingPages = q ? pages.filter((page) => rank(q, en.nav.items[page.id]) !== undefined) : pages;
  const matchingRoutes = q
    ? (routes.data?.data.items ?? [])
        .map((route) => ({ route, score: rank(q, route.displayName, route.shortName, route.longName) }))
        .filter((entry): entry is { route: (typeof entry)['route']; score: number } => entry.score !== undefined)
        .sort((a, b) => a.score - b.score || (a.route.sortOrder ?? 0) - (b.route.sortOrder ?? 0))
        .slice(0, MAX_RESULTS)
        .map((entry) => entry.route)
    : [];
  const matchingStops = searchStops ? (stops.data?.data.items ?? []) : [];
  const stopsBusy = searchStops && stops.isFetching;
  const nothing =
    q !== '' &&
    matchingPages.length === 0 &&
    matchingRoutes.length === 0 &&
    matchingStops.length === 0 &&
    !stopsBusy &&
    !stops.isError;

  const close = () => {
    onOpenChange(false);
    setQuery('');
  };
  const go = (to: string, search?: Record<string, string>) => {
    close();
    void navigate({ to: to as '/', search: search as never });
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setQuery('');
        onOpenChange(next);
      }}
    >
      <DialogContent className="top-[15vh] w-[min(560px,calc(100vw-2rem))] translate-y-0 overflow-hidden p-0">
        <DialogTitle className="sr-only">{en.search.label}</DialogTitle>
        <Command shouldFilter={false} label={en.search.label}>
          <CommandInput value={query} onValueChange={setQuery} placeholder={en.search.placeholder} />
          <CommandList>
            {nothing ? <CommandEmpty>{en.search.noMatches(query.trim())}</CommandEmpty> : null}
            {matchingPages.length > 0 ? (
              <CommandGroup heading={en.search.groups.pages}>
                {matchingPages.map((page) => (
                  <CommandItem
                    key={page.id}
                    value={`page:${page.id}`}
                    onSelect={() => {
                      go(page.to);
                    }}
                  >
                    <page.icon aria-hidden="true" />
                    {en.nav.items[page.id]}
                  </CommandItem>
                ))}
              </CommandGroup>
            ) : null}
            {matchingRoutes.length > 0 ? (
              <CommandGroup heading={en.search.groups.routes}>
                {matchingRoutes.map((route) => (
                  <CommandItem
                    key={route.routeId}
                    value={`route:${route.routeId}`}
                    onSelect={() => {
                      go(MAP_PATH, { route: route.routeId });
                    }}
                  >
                    <RouteBadge
                      routeId={route.routeId}
                      displayName={route.displayName}
                      color={route.color}
                      textColor={route.textColor}
                      size="md"
                    />
                    <span className="truncate">{route.longName ?? route.displayName}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            ) : null}
            {matchingStops.length > 0 || stops.isError ? (
              <CommandGroup heading={en.search.groups.stops}>
                {stops.isError ? (
                  <p className="px-2 py-1.5 text-sm text-tone-danger-fg">{en.search.stopsFailed}</p>
                ) : (
                  matchingStops.map((stop) => (
                    <CommandItem
                      key={stop.stopId}
                      value={`stop:${stop.stopId}`}
                      onSelect={() => {
                        go(`/stops/${stop.stopId}`);
                      }}
                    >
                      <MapPin aria-hidden="true" />
                      <span className="truncate">{stop.name}</span>
                      {stop.code ? (
                        <span className="ml-auto font-mono text-xs text-muted-foreground">{stop.code}</span>
                      ) : null}
                    </CommandItem>
                  ))
                )}
              </CommandGroup>
            ) : null}
          </CommandList>
        </Command>
      </DialogContent>
    </Dialog>
  );
}
