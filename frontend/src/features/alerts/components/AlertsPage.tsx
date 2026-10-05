import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { BellOff, SearchX } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { useAccess } from '@/app/access';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { NewItemsPill } from '@/components/NewItemsPill';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RelativeTime } from '@/components/RelativeTime';
import { RouteBadge } from '@/components/RouteBadge';
import { RouteSelect } from '@/components/RouteSelect';
import { SegmentedControl } from '@/components/SegmentedControl';
import { SplitView } from '@/components/SplitView';
import { ToneBadge } from '@/components/ToneBadge';
import { toneClasses } from '@/components/tone';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { dropAlert } from '@/features/alerts/cache';
import { AlertDetail } from '@/features/alerts/components/AlertDetail';
import { alertVisual, isUnread, summaryLine, type Alert } from '@/lib/alert-display';
import { alertBadgeQuery, alertListQuery, routesQuery, type AlertFilters } from '@/features/alerts/queries';
import { ALERT_TYPES, AUDIENCES, WINDOWS, type AlertsSearch, type AlertState } from '@/features/alerts/search';
import { useQueryClient } from '@tanstack/react-query';
import { en } from '@/i18n/en';
import { alertsCopy } from '@/i18n/alerts';
import { useDocumentTitle, useMediaQuery } from '@/lib/browser';
import { cn } from '@/lib/utils';
import { useRealtime } from '@/realtime/useRealtime';

/** Below this scroll offset new alerts slide in at the top; further down they wait behind the pill (§4). */
const PILL_AFTER_PX = 120;
/** A new row keeps its tint this long. */
const HIGHLIGHT_MS = 3_000;
/** At most one "New alert: …" announcement this often; more are counted (§4). */
const ANNOUNCE_EVERY_MS = 10_000;
/** Pages fetched to find an alert opened by link before giving up (§6.2). */
const FIND_PAGES = 3;
const MAX_STREAM_ROUTES = 20;

const STATE_TABS: AlertState[] = ['open', 'unacknowledged', 'all'];

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) ||
    target.closest('.cm-editor') !== null
  );
}

interface RouteLook {
  displayName: string;
  color?: string;
  textColor?: string;
}

function AlertItem({
  alert,
  selected,
  signedIn,
  fresh,
  route,
  leaving,
}: {
  alert: Alert;
  selected: boolean;
  signedIn: boolean;
  fresh: boolean;
  route?: RouteLook;
  leaving: boolean;
}) {
  const visual = alertVisual(alert);
  const summary = summaryLine(alert);
  const severity = alert.severity >= 2 ? 'danger' : alert.severity === 1 ? 'warning' : 'neutral';
  return (
    <li>
      <Link
        from="/alerts"
        to="/alerts"
        search={(previous: AlertsSearch) => ({ ...previous, alert: alert.id })}
        aria-current={selected ? 'true' : undefined}
        data-alert-id={alert.id}
        className={cn(
          'grid grid-cols-[32px_minmax(0,1fr)] gap-3 rounded-lg border border-transparent px-3.5 py-3 hover:bg-surface motion-safe:transition-[background-color,opacity] motion-safe:duration-500',
          selected && 'border-primary/20 bg-primary-soft hover:bg-primary-soft',
          fresh && !selected && 'bg-tone-info-bg',
          (alert.resolvedAt !== undefined || leaving) && 'opacity-60',
        )}
      >
        <span
          className={cn('grid size-8 place-items-center rounded-[9px]', toneClasses(visual.tone).surface)}
          aria-hidden="true"
        >
          <visual.icon className="size-4" strokeWidth={1.75} />
        </span>
        <span className="min-w-0">
          <span className="flex items-center justify-between gap-2">
            <span
              className={cn(
                'truncate text-[13.5px] font-semibold tracking-[-0.012em]',
                alert.resolvedAt && 'text-muted-foreground',
              )}
            >
              {alert.title}
            </span>
            {signedIn && isUnread(alert) ? (
              <span
                role="img"
                aria-label={alertsCopy.alerts.unread}
                className="size-[7px] shrink-0 rounded-full bg-primary"
              />
            ) : null}
          </span>
          {route || summary ? (
            <span className="mt-1.25 flex items-center gap-1.5 text-xs text-muted-foreground">
              {route && alert.routeId ? (
                <RouteBadge
                  routeId={alert.routeId}
                  displayName={route.displayName}
                  color={route.color}
                  textColor={route.textColor}
                  size="sm"
                />
              ) : null}
              {summary ? <span className="truncate">{summary}</span> : null}
            </span>
          ) : null}
          <span className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground tabular-nums">
            <ToneBadge
              tone={severity}
              size="sm"
              label={alertsCopy.alerts.severityShort[alert.severity] ?? String(alert.severity)}
            />
            <RelativeTime at={alert.createdAt} axis="audit" />
          </span>
        </span>
      </Link>
    </li>
  );
}

function ListSkeleton() {
  return (
    <div aria-busy="true" className="flex flex-col gap-1 px-2">
      <span role="status" className="sr-only">
        {en.states.loading}
      </span>
      {Array.from({ length: 8 }, (_, index) => (
        <Skeleton key={index} className="h-[72px] rounded-lg" />
      ))}
    </div>
  );
}

/** The alert feed: list and detail side by side (DOC-36 screens/alert-feed). */
export function AlertsPage({ search }: { search: AlertsSearch }) {
  useDocumentTitle(alertsCopy.alerts.title);
  const navigate = useNavigate({ from: '/alerts' });
  const queryClient = useQueryClient();
  const access = useAccess();
  const staff = access.role !== undefined;
  const signedIn = !access.pending && access.signedIn;
  const wide = useMediaQuery('(min-width: 1024px)', true);

  // Anonymous users see public alerts only: no audience filter, no "Unacknowledged" (§1, §2).
  const state: AlertState = search.state === 'unacknowledged' && !signedIn ? 'open' : (search.state ?? 'open');
  const filters: AlertFilters = {
    state,
    audience: staff ? search.audience : undefined,
    type: search.type,
    severity: search.severity,
    routeId: search.route,
    window: search.window ?? '24h',
  };
  const filtered = [filters.audience, filters.type, filters.severity, filters.routeId].some(
    (value) => (value?.length ?? 0) > 0,
  );

  useRealtime({
    channels: ['alerts'],
    routeIds: search.route && search.route.length <= MAX_STREAM_ROUTES ? search.route : [],
  });

  const list = useInfiniteQuery(alertListQuery(filters));
  const badge = useQuery({ ...alertBadgeQuery(), enabled: signedIn });
  const routes = useQuery(routesQuery());
  const routeById = new Map((routes.data?.data.items ?? []).map((route) => [route.routeId, route]));
  const alerts = useMemo(() => list.data?.pages.flatMap((page) => page.data.items) ?? [], [list.data]);
  const unacknowledged = badge.data?.data.items.filter(isUnread).length;

  const setSearch = useCallback(
    (change: Partial<AlertsSearch>, replace = true) => {
      void navigate({ search: (previous: AlertsSearch) => ({ ...previous, ...change }), replace });
    },
    [navigate],
  );

  // New alerts at the head of the list: tinted for 3 s, or held behind the pill when the list is scrolled (§6).
  const scroller = useRef<HTMLDivElement>(null);
  const [seen, setSeen] = useState<{ key: string; ids: Set<string> }>({ key: '', ids: new Set() });
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const [pill, setPill] = useState(0);
  const [scrolled, setScrolled] = useState(false);
  const [queue, setQueue] = useState<string[]>([]);
  const [cooling, setCooling] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const listKey = JSON.stringify(filters);
  // Compared during render (no effect): rows above the first one already seen are new.
  if (list.data && (seen.key !== listKey || alerts.some((alert) => !seen.ids.has(alert.id)))) {
    if (seen.key === listKey) {
      const firstKnown = alerts.findIndex((alert) => seen.ids.has(alert.id));
      const added = alerts.slice(0, Math.max(firstKnown, 0));
      if (added.length > 0) {
        setFresh(new Set(added.map((alert) => alert.id)));
        if (scrolled || (!wide && search.alert)) setPill((n) => n + added.length);
        setQueue((current) => [...current, ...added.map((alert) => alert.title)]);
      }
    } else {
      setPill(0);
    }
    setSeen({ key: listKey, ids: new Set(alerts.map((alert) => alert.id)) });
  }
  useEffect(() => {
    if (fresh.size === 0) return;
    const timer = setTimeout(() => {
      setFresh(new Set());
    }, HIGHLIGHT_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [fresh]);
  // Screen readers hear one "New alert: …" at most every 10 s; what arrives meanwhile is counted (§4).
  useEffect(() => {
    if (queue.length === 0 || cooling) return;
    const timer = setTimeout(() => {
      setAnnouncement(
        queue.length === 1
          ? alertsCopy.alerts.announce.one(queue[0] ?? '')
          : alertsCopy.alerts.announce.many(queue.length),
      );
      setQueue([]);
      setCooling(true);
    }, 0);
    return () => {
      clearTimeout(timer);
    };
  }, [queue, cooling]);
  useEffect(() => {
    if (!cooling) return;
    const timer = setTimeout(() => {
      setCooling(false);
    }, ANNOUNCE_EVERY_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [cooling]);

  const selected = alerts.find((alert) => alert.id === search.alert);

  // Desktop opens the first alert, so the detail panel is not empty (§5).
  const first = alerts[0];
  useEffect(() => {
    if (wide && !search.alert && first) setSearch({ alert: first.id });
  }, [wide, search.alert, first, setSearch]);

  // An alert opened by link that is not on the loaded pages: load up to three more (§6.2).
  const pagesLoaded = list.data?.pages.length ?? 0;
  const searching = Boolean(search.alert) && !selected && list.hasNextPage && pagesLoaded < FIND_PAGES + 1;
  useEffect(() => {
    if (searching && !list.isFetchingNextPage) void list.fetchNextPage();
  }, [searching, list]);

  // j / k move through the list, Esc closes the detail (DOC-34 §4.3).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target)) return;
      if (event.key === 'Escape' && search.alert) {
        setSearch({ alert: undefined });
        return;
      }
      if (event.key !== 'j' && event.key !== 'k') return;
      const index = alerts.findIndex((alert) => alert.id === search.alert);
      const next = alerts[event.key === 'j' ? Math.min(alerts.length - 1, index + 1) : Math.max(0, index - 1)];
      if (next) {
        event.preventDefault();
        setSearch({ alert: next.id }, false);
        scroller.current?.querySelector(`[data-alert-id="${next.id}"]`)?.scrollIntoView({ block: 'nearest' });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [alerts, search.alert, setSearch]);

  // The record behind the open alert is gone for this caller: its row leaves, the panel says so (§5, §6).
  const [goneId, setGoneId] = useState<string>();
  const onGone = useCallback(() => {
    if (!search.alert) return;
    setGoneId(search.alert);
    dropAlert(queryClient, search.alert);
  }, [queryClient, search.alert]);

  const clearFilters = () => {
    setSearch({ audience: undefined, type: undefined, severity: undefined, route: undefined });
  };

  const tabs = (
    <div role="tablist" aria-label={alertsCopy.alerts.filters.period} className="flex border-b border-border">
      {STATE_TABS.filter((tab) => tab !== 'unacknowledged' || signedIn).map((tab) => {
        const active = state === tab;
        return (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => {
              setSearch({ state: tab === 'open' ? undefined : tab, alert: undefined });
            }}
            className={cn(
              'relative inline-flex h-9.5 items-center gap-1.75 px-2.5 text-sm font-medium text-muted-foreground hover:text-foreground',
              active &&
                'text-foreground after:absolute after:inset-x-2 after:-bottom-px after:h-0.5 after:rounded-full after:bg-foreground',
            )}
          >
            {alertsCopy.alerts.tabs[tab]}
            {tab === 'unacknowledged' && unacknowledged ? (
              <span
                className={cn(
                  'inline-flex h-4.5 items-center rounded-full px-1.5 text-[11.5px] tabular-nums',
                  active ? 'bg-foreground text-card' : 'bg-muted text-muted-foreground',
                )}
              >
                {en.nav.count(unacknowledged)}
              </span>
            ) : null}
          </button>
        );
      })}
    </div>
  );

  const filterBar = (
    <div className="flex flex-wrap items-center gap-1.5">
      <MultiSelectFilter
        label={alertsCopy.alerts.filters.severity}
        options={[2, 1, 0].map((value) => ({ value, label: en.severity[value as 0 | 1 | 2] }))}
        value={search.severity ?? []}
        onChange={(value) => {
          setSearch({ severity: value.length > 0 ? value : undefined });
        }}
      />
      <MultiSelectFilter
        label={alertsCopy.alerts.filters.type}
        options={ALERT_TYPES.map((value) => ({ value, label: alertsCopy.alertType[value] ?? value }))}
        value={search.type ?? []}
        onChange={(value) => {
          setSearch({ type: value.length > 0 ? value : undefined });
        }}
      />
      {staff ? (
        <MultiSelectFilter
          label={alertsCopy.alerts.filters.audience}
          options={AUDIENCES.map((value) => ({ value, label: alertsCopy.audience[value] ?? value }))}
          value={search.audience ?? []}
          onChange={(value) => {
            setSearch({ audience: value.length > 0 ? value : undefined });
          }}
        />
      ) : null}
      <div className="w-44">
        <RouteSelect
          routes={routes.data?.data.items ?? []}
          value={search.route ?? []}
          placeholder={alertsCopy.alerts.filters.route}
          onChange={(value) => {
            setSearch({ route: value.length > 0 ? value : undefined });
          }}
        />
      </div>
      <SegmentedControl
        size="sm"
        label={alertsCopy.alerts.filters.period}
        value={filters.window}
        options={WINDOWS.map((value) => ({ value, label: alertsCopy.alerts.filters.window[value] }))}
        onChange={(value) => {
          setSearch({ window: value === '24h' ? undefined : value });
        }}
      />
    </div>
  );

  const listPanel = (
    <section
      aria-label={alertsCopy.alerts.list}
      className="flex min-h-0 flex-col rounded-lg border border-border bg-card shadow-sm"
    >
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {pill > 0 ? (
        <div className="flex justify-center px-3 pt-3">
          <NewItemsPill
            count={pill}
            onShow={() => {
              scroller.current?.scrollTo({ top: 0 });
              setPill(0);
            }}
          />
        </div>
      ) : null}
      <div
        ref={scroller}
        onScroll={(event) => {
          const down = event.currentTarget.scrollTop > PILL_AFTER_PX;
          if (down !== scrolled) setScrolled(down);
          if (!down && pill > 0) setPill(0);
        }}
        className="min-h-0 overflow-y-auto py-2 lg:max-h-[calc(100svh-15rem)]"
      >
        {list.isPending ? (
          <ListSkeleton />
        ) : list.isError && alerts.length === 0 ? (
          <ErrorState
            error={list.error}
            variant="block"
            panel={alertsCopy.alerts.panel}
            onRetry={() => void list.refetch()}
          />
        ) : alerts.length === 0 ? (
          filtered ? (
            <EmptyState
              icon={SearchX}
              title={alertsCopy.alerts.empty.filteredTitle}
              action={{ label: en.common.clearFilters, onClick: clearFilters }}
            />
          ) : (
            <EmptyState
              icon={BellOff}
              title={alertsCopy.alerts.empty.openTitle}
              description={alertsCopy.alerts.empty.openBody}
            />
          )
        ) : (
          <>
            {list.isError ? (
              <div className="px-2 pb-2">
                <ErrorState error={list.error} variant="inline" onRetry={() => void list.refetch()} />
              </div>
            ) : null}
            <ul className={cn('flex flex-col gap-1 px-2', list.isPlaceholderData && 'opacity-60')}>
              {alerts.map((alert) => (
                <AlertItem
                  key={alert.id}
                  alert={alert}
                  selected={alert.id === search.alert}
                  signedIn={signedIn}
                  fresh={fresh.has(alert.id)}
                  leaving={state === 'unacknowledged' && alert.acknowledgedAt !== undefined}
                  route={alert.routeId ? routeById.get(alert.routeId) : undefined}
                />
              ))}
            </ul>
            {list.hasNextPage ? (
              <div className="flex justify-center p-3">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={list.isFetchingNextPage}
                  onClick={() => void list.fetchNextPage()}
                >
                  {alertsCopy.alerts.actions.loadOlder}
                </Button>
              </div>
            ) : null}
          </>
        )}
      </div>
    </section>
  );

  const detail =
    search.alert && search.alert === goneId ? (
      <EmptyState title={alertsCopy.alerts.gone} />
    ) : selected ? (
      <AlertDetail
        key={selected.id}
        alert={selected}
        access={access}
        route={selected.routeId ? routeById.get(selected.routeId) : undefined}
        onGone={onGone}
        onBack={
          wide
            ? undefined
            : () => {
                setSearch({ alert: undefined }, false);
              }
        }
      />
    ) : search.alert && !list.isPending ? (
      searching ? (
        <PanelSkeleton variant="detail" />
      ) : (
        <EmptyState
          title={alertsCopy.alerts.notInList}
          action={{
            label: alertsCopy.alerts.showLastWeek,
            onClick: () => {
              setSearch({ window: '7d', state: 'all' });
            },
          }}
        />
      )
    ) : null;

  const readOnly =
    access.role === 'viewer' ? (
      <Tooltip>
        <TooltipTrigger asChild>
          <span tabIndex={0}>
            <ToneBadge tone="neutral" label={en.nav.readOnly} />
          </span>
        </TooltipTrigger>
        <TooltipContent>{en.nav.readOnlyHelp}</TooltipContent>
      </Tooltip>
    ) : undefined;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={alertsCopy.alerts.title} actions={readOnly} />
      {tabs}
      {filterBar}
      {wide ? (
        <SplitView
          list={listPanel}
          detail={detail}
          emptyDetail={
            list.isPending ? <PanelSkeleton variant="detail" /> : <EmptyState title={alertsCopy.alerts.selectOne} />
          }
        />
      ) : search.alert ? (
        <div className="rounded-lg border border-border bg-card shadow-sm">{detail}</div>
      ) : (
        listPanel
      )}
    </div>
  );
}
