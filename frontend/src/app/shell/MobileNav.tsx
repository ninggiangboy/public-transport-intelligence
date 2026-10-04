import { Link, useRouterState } from '@tanstack/react-router';
import { Bell, Ellipsis, Map as MapIcon, MapPin, Menu, Search } from 'lucide-react';
import { lazy, Suspense, useState } from 'react';

import type { Access } from '@/app/access';
import { AccountMenu } from '@/app/shell/AccountMenu';
import type { SidebarProps } from '@/app/shell/AppSidebar';
import { Logo } from '@/app/shell/Logo';
import { homePath, isItemActive, NAV_ITEMS, type NavItem } from '@/app/shell/nav-items';
import { RealtimeStatusDot } from '@/app/shell/RealtimeStatusDot';
import type { NavCounts } from '@/app/shell/use-nav-counts';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';
import type { RealtimeState } from '@/realtime/useRealtime';

const loadSheets = () => import('@/app/shell/mobile-sheets');
const MenuSheet = lazy(() => loadSheets().then((module) => ({ default: module.MenuSheet })));
const MoreSheet = lazy(() => loadSheets().then((module) => ({ default: module.MoreSheet })));

interface MobileTopBarProps extends SidebarProps {
  /** 768–1023 px: a menu button opens the sidebar in a sheet. */
  withMenu: boolean;
}

/** 48 px bar under 1024 px: logo, stream state, search and account (DOC-34 §4.2). */
export function MobileTopBar({ withMenu, ...sidebar }: MobileTopBarProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  // Mounted from the first opening on, so that the sheet can animate closed.
  const [menuUsed, setMenuUsed] = useState(false);
  return (
    <header className="sticky top-0 z-(--z-banner) flex h-12 shrink-0 items-center gap-2 border-b border-border bg-surface px-3">
      {withMenu ? (
        <>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={en.nav.openMenu}
            aria-haspopup="dialog"
            aria-expanded={menuOpen}
            onPointerEnter={() => void loadSheets()}
            onClick={() => {
              setMenuUsed(true);
              setMenuOpen(true);
            }}
          >
            <Menu aria-hidden="true" />
          </Button>
          {menuUsed ? (
            <Suspense>
              <MenuSheet open={menuOpen} onOpenChange={setMenuOpen} sidebar={sidebar} />
            </Suspense>
          ) : null}
        </>
      ) : null}
      <Link to={homePath(sidebar.access) as '/'} aria-label={en.brand.home} className="rounded-md">
        <Logo />
      </Link>
      <div className="ml-auto flex items-center gap-2">
        <RealtimeStatusDot state={sidebar.realtime} compact />
        <Button variant="ghost" size="icon" aria-label={en.search.open} onClick={sidebar.onOpenSearch}>
          <Search aria-hidden="true" />
        </Button>
        <AccountMenu access={sidebar.access} onShowShortcuts={sidebar.onShowShortcuts} layout="bar" />
      </div>
    </header>
  );
}

const TABS: { id: 'map' | 'stops' | 'alerts'; icon: typeof MapIcon }[] = [
  { id: 'map', icon: MapIcon },
  { id: 'stops', icon: MapPin },
  { id: 'alerts', icon: Bell },
];

interface BottomTabsProps {
  access: Access;
  items: readonly NavItem[];
  counts: NavCounts;
  realtime: RealtimeState;
}

/** Tab bar under 768 px: "Map", "Stops", "Alerts" and "More", which holds the rest of the menu (DOC-34 §4.2). */
export function BottomTabs({ access, items, counts }: BottomTabsProps) {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const [moreOpen, setMoreOpen] = useState(false);
  const [moreUsed, setMoreUsed] = useState(false);
  const tabItem = (id: string) => NAV_ITEMS.find((item) => item.id === id);
  const inMore = (item: NavItem) => !TABS.some((tab) => tab.id === item.id);
  const moreActive = items.some((item) => inMore(item) && isItemActive(item, pathname));
  const alerts = counts.unacknowledgedAlerts;
  const tabClass =
    'flex min-h-11 flex-1 flex-col items-center justify-center gap-0.5 text-[11px] font-medium text-muted-foreground';

  return (
    <nav
      aria-label={en.nav.primary}
      className="sticky bottom-0 z-(--z-banner) flex shrink-0 border-t border-border bg-surface pb-[env(safe-area-inset-bottom)]"
    >
      {TABS.map((tab) => {
        const item = tabItem(tab.id);
        if (!item) return null;
        const active = isItemActive(item, pathname);
        return (
          <Link
            key={tab.id}
            to={item.to as '/'}
            aria-current={active ? 'page' : undefined}
            className={cn(tabClass, active && 'text-primary')}
          >
            <span className="relative">
              <tab.icon className="size-5" strokeWidth={1.75} aria-hidden="true" />
              {tab.id === 'alerts' && alerts ? (
                <span className="absolute -top-1.5 left-3.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-tone-danger-solid px-1 text-[10px] font-semibold text-white">
                  <span aria-hidden="true">{en.nav.count(alerts)}</span>
                  <span className="sr-only">{en.nav.unacknowledgedAlerts(alerts)}</span>
                </span>
              ) : null}
            </span>
            {en.nav.mobile[tab.id]}
          </Link>
        );
      })}
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={moreOpen}
        className={cn(tabClass, moreActive && 'text-primary')}
        onPointerEnter={() => void loadSheets()}
        onClick={() => {
          setMoreUsed(true);
          setMoreOpen(true);
        }}
      >
        <Ellipsis className="size-5" strokeWidth={1.75} aria-hidden="true" />
        {en.nav.mobile.more}
      </button>
      {moreUsed ? (
        <Suspense>
          <MoreSheet
            open={moreOpen}
            onOpenChange={setMoreOpen}
            access={access}
            items={items}
            counts={counts}
            inMore={inMore}
          />
        </Suspense>
      ) : null}
    </nav>
  );
}
