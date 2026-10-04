import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Search, SearchX } from 'lucide-react';
import { useEffect, useId, useState, type ReactNode } from 'react';

import type { components } from '@/api/generated/schema';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { Button } from '@/components/ui/button';
import { routesQuery, stopSearchQuery } from '@/features/stops/queries';
import { rememberRecent, useStopLists, type StopRef } from '@/lib/stop-lists';
import { en } from '@/i18n/en';

type RouteItem = components['schemas']['RouteItemResponse'];

const DEBOUNCE_MS = 250;
const MIN_CHARS = 2;

function StopLink({
  stop,
  routeIds,
  routes,
}: {
  stop: StopRef;
  routeIds?: readonly string[];
  routes?: Map<string, RouteItem>;
}) {
  return (
    <li>
      <Link
        to="/stops/$stopId"
        params={{ stopId: stop.stopId }}
        onClick={() => {
          rememberRecent(stop);
        }}
        className="flex min-h-11 items-center gap-3 rounded-md px-3 py-2 hover:bg-muted"
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate text-base font-medium">{stop.name}</span>
          {routeIds && routeIds.length > 0 ? (
            <span className="mt-1 flex flex-wrap gap-1">
              {routeIds.map((routeId) => {
                const route = routes?.get(routeId);
                return (
                  <RouteBadge
                    key={routeId}
                    routeId={routeId}
                    displayName={route?.displayName ?? routeId}
                    color={route?.color}
                    textColor={route?.textColor}
                    size="sm"
                  />
                );
              })}
            </span>
          ) : null}
        </span>
        {stop.code ? <span className="font-mono text-sm text-muted-foreground">{stop.code}</span> : null}
      </Link>
    </li>
  );
}

function StopList({ title, stops, action }: { title: string; stops: StopRef[]; action?: ReactNode }) {
  if (stops.length === 0) return null;
  return (
    <section className="flex flex-col gap-1">
      <div className="flex items-center justify-between px-3">
        <h2 className="text-sm font-medium text-muted-foreground">{title}</h2>
        {action}
      </div>
      <ul>
        {stops.map((stop) => (
          <StopLink key={stop.stopId} stop={stop} />
        ))}
      </ul>
    </section>
  );
}

function Results({ q }: { q: string }) {
  const search = useQuery(stopSearchQuery(q));
  const routes = useQuery(routesQuery());
  const byId = new Map((routes.data?.data.items ?? []).map((route) => [route.routeId, route]));
  if (search.isPending) return <PanelSkeleton variant="list" />;
  if (search.isError)
    return (
      <ErrorState
        error={search.error}
        variant="block"
        panel={en.stops.find.failed}
        onRetry={() => void search.refetch()}
      />
    );
  const items = search.data.data.items;
  if (items.length === 0) {
    return <EmptyState icon={SearchX} title={en.stops.find.noMatchTitle(q)} description={en.stops.find.noMatchBody} />;
  }
  return (
    <section className="flex flex-col gap-1">
      <h2 className="sr-only">{en.stops.find.results}</h2>
      <ul>
        {items.map((stop) => (
          <StopLink key={stop.stopId} stop={stop} routeIds={stop.routeIds} routes={byId} />
        ))}
      </ul>
    </section>
  );
}

interface StopFinderProps {
  /** The text in the box; the caller keeps it (in the URL on /stops). */
  q: string;
  onQChange: (q: string) => void;
  /** Saved and recent stops while the box is empty. */
  showLists?: boolean;
}

/** Search by stop name or number (E-06), with saved and recent stops below an empty box (screens/stop-detail §3). */
export function StopFinder({ q, onQChange, showLists = true }: StopFinderProps) {
  const inputId = useId();
  const [text, setText] = useState(q);
  // The last `q` this box wrote, and the last `q` it saw: a `q` it did not write (a link, Back) replaces the text.
  const [written, setWritten] = useState(q);
  const [seen, setSeen] = useState(q);
  if (q !== seen) {
    setSeen(q);
    if (q !== written) setText(q);
  }
  const lists = useStopLists();

  // The URL follows the box 250 ms after the last keystroke.
  useEffect(() => {
    const next = text.trim();
    if (next === q) return;
    const timer = setTimeout(() => {
      setWritten(next);
      onQChange(next);
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [text, q, onQChange]);

  const query = q.length >= MIN_CHARS ? q : '';
  return (
    <div className="flex flex-col gap-4">
      <div className="relative">
        <label htmlFor={inputId} className="sr-only">
          {en.stops.find.label}
        </label>
        <Search
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden="true"
        />
        <input
          id={inputId}
          type="search"
          autoComplete="off"
          value={text}
          onChange={(event) => {
            setText(event.target.value);
          }}
          placeholder={en.stops.find.placeholder}
          className="h-11 w-full rounded-lg border border-input bg-card pr-3 pl-9 text-base shadow-xs placeholder:text-muted-foreground"
        />
      </div>
      {query ? (
        <Results q={query} />
      ) : showLists ? (
        <>
          <StopList title={en.stops.find.saved} stops={lists.saved} />
          <StopList
            title={en.stops.find.recent}
            stops={lists.recent}
            action={
              <Button variant="ghost" size="sm" onClick={lists.clearRecent}>
                {en.stops.find.clear}
              </Button>
            }
          />
        </>
      ) : null}
    </div>
  );
}
