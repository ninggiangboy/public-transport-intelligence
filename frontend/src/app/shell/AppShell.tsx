import { Outlet, useMatches, useRouterState } from '@tanstack/react-router';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';

import { useAccess } from '@/app/access';
import { useEnv } from '@/app/env-context';
import { AppSidebar, type SidebarProps } from '@/app/shell/AppSidebar';
import { BottomTabs, MobileTopBar } from '@/app/shell/MobileNav';
import { visibleItems } from '@/app/shell/nav-items';
import { ShellFooter } from '@/app/shell/ShellFooter';
import { OpsNarrowNotice, StaleBanner } from '@/app/shell/StaleBanner';
import { useNavCounts } from '@/app/shell/use-nav-counts';
import { en } from '@/i18n/en';
import { useMediaQuery, useStoredFlag } from '@/lib/browser';
import { cn } from '@/lib/utils';
import { useRealtime } from '@/realtime/useRealtime';

/** Tailwind's breakpoints (DOC-34 §6). Where `matchMedia` is missing (jsdom) the shell lays out for a wide screen. */
const XL = '(min-width: 1280px)';
const LG = '(min-width: 1024px)';
const MD = '(min-width: 768px)';

// Loaded on first use, to keep cmdk out of the initial bundle (DOC-34 §7).
const CommandSearch = lazy(() =>
  import('@/app/shell/CommandSearch').then((module) => ({ default: module.CommandSearch })),
);
// Toasts come from src/lib/notify.ts, which waits for this to mount.
const Toaster = lazy(() => import('@/components/ui/sonner').then((module) => ({ default: module.Toaster })));
const KeyboardShortcutsDialog = lazy(() =>
  import('@/app/shell/KeyboardShortcutsDialog').then((module) => ({ default: module.KeyboardShortcutsDialog })),
);

const SIDEBAR_COLLAPSED_KEY = 'pti.sidebar.collapsed';

/** Moves focus to the new page's `h1` after a navigation, so screen readers announce it (screens/shell §6). */
function useFocusOnNavigate() {
  const pathname = useRouterState({ select: (state) => state.resolvedLocation?.pathname });
  const previous = useRef(pathname);
  useEffect(() => {
    const before = previous.current;
    previous.current = pathname;
    if (before === undefined || before === pathname) return;
    const frame = requestAnimationFrame(() => {
      const main = document.getElementById('main');
      const heading = main?.querySelector('h1');
      if (heading && !heading.hasAttribute('tabindex')) heading.setAttribute('tabindex', '-1');
      (heading ?? main)?.focus();
    });
    return () => {
      cancelAnimationFrame(frame);
    };
  }, [pathname]);
}

/** Every page of the app inside one frame: sidebar or mobile bars, banners, content and footer (DOC-34 §4.2). */
export function AppShell() {
  const bare = useMatches({ select: (matches) => matches.some((match) => match.staticData.bare) });
  if (bare) return <Outlet />;
  return <Shell />;
}

function Shell() {
  const access = useAccess();
  const appEnv = useEnv();
  const items = visibleItems(access, appEnv);
  const counts = useNavCounts(access);
  // The shell always listens to alerts (screens/shell §5); pages add their own channels.
  const realtime = useRealtime({ channels: ['alerts'] });

  const wide = useMediaQuery(XL, true);
  const desktop = useMediaQuery(LG, true);
  const tablet = useMediaQuery(MD, true);
  const [railPreferred, setRailPreferred] = useStoredFlag('local', SIDEBAR_COLLAPSED_KEY, true);
  const collapsed = desktop && !wide && railPreferred;

  const [searchOpen, setSearchOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  // Mounted from the first opening on, so that closing them still animates and keeps their state.
  const [searchUsed, setSearchUsed] = useState(false);
  const [shortcutsUsed, setShortcutsUsed] = useState(false);
  const openSearch = () => {
    setSearchUsed(true);
    setSearchOpen(true);
  };

  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const { fullBleed, hideStaleBanner } = useMatches({
    select: (matches) => ({
      fullBleed: matches.some((match) => match.staticData.fullBleed),
      hideStaleBanner: matches.some((match) => match.staticData.hideStaleBanner),
    }),
    structuralSharing: true,
  });
  const ops = pathname === '/ops' || pathname.startsWith('/ops/');
  const bannerClass = fullBleed ? 'px-3 pt-3' : 'mb-4';

  useFocusOnNavigate();

  // ⌘K / Ctrl K opens the search from anywhere, even from a text field (DOC-34 §4.3).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchUsed(true);
        setSearchOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, []);

  const sidebar: SidebarProps = {
    access,
    items,
    counts,
    realtime,
    onOpenSearch: openSearch,
    onShowShortcuts: () => {
      setShortcutsUsed(true);
      setShortcutsOpen(true);
    },
  };

  return (
    <div className="flex min-h-svh bg-background text-foreground">
      <a
        href="#main"
        className="sr-only z-(--z-toast) rounded-md bg-card px-3 py-2 text-sm font-medium shadow-md focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        {en.skipToContent}
      </a>
      {desktop ? (
        <AppSidebar
          {...sidebar}
          collapsed={collapsed}
          onToggleCollapsed={
            wide
              ? undefined
              : () => {
                  setRailPreferred(!railPreferred);
                }
          }
        />
      ) : null}
      <div className="flex min-w-0 flex-1 flex-col">
        {desktop ? null : <MobileTopBar {...sidebar} withMenu={tablet} />}
        <main
          id="main"
          tabIndex={-1}
          className={cn(
            'flex min-w-0 flex-1 flex-col outline-none',
            fullBleed ? 'relative min-h-0' : 'px-4 py-5 md:px-7 md:py-6',
          )}
        >
          {hideStaleBanner ? null : <StaleBanner realtime={realtime} className={bannerClass} />}
          {ops && !wide ? <OpsNarrowNotice className={bannerClass} /> : null}
          <Outlet />
          {fullBleed ? null : <ShellFooter />}
        </main>
        {tablet ? null : <BottomTabs access={access} items={items} counts={counts} realtime={realtime} />}
      </div>
      <Suspense>
        {searchUsed ? <CommandSearch open={searchOpen} onOpenChange={setSearchOpen} pages={items} /> : null}
        {shortcutsUsed ? <KeyboardShortcutsDialog open={shortcutsOpen} onOpenChange={setShortcutsOpen} /> : null}
      </Suspense>
      <Suspense>
        <Toaster position={tablet ? 'bottom-right' : 'top-center'} />
      </Suspense>
    </div>
  );
}
