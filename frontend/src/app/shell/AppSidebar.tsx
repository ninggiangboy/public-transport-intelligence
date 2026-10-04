import { Link } from '@tanstack/react-router';
import { PanelLeftClose, PanelLeftOpen, Search } from 'lucide-react';

import type { Access } from '@/app/access';
import { AccountMenu } from '@/app/shell/AccountMenu';
import { LiveFeedCard } from '@/app/shell/LiveFeedCard';
import { Logo } from '@/app/shell/Logo';
import { NavList } from '@/app/shell/NavList';
import { homePath, type NavItem } from '@/app/shell/nav-items';
import type { NavCounts } from '@/app/shell/use-nav-counts';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';
import type { RealtimeState } from '@/realtime/useRealtime';

export interface SidebarProps {
  access: Access;
  items: readonly NavItem[];
  counts: NavCounts;
  realtime: RealtimeState;
  onOpenSearch: () => void;
  onShowShortcuts: () => void;
  /** Rail of 64 px (1024–1279 px, by the user's choice). */
  collapsed?: boolean;
  onToggleCollapsed?: () => void;
  /** In a sheet (768–1023 px): close it after navigating. */
  onNavigate?: () => void;
  className?: string;
}

/**
 * Brand, search, the three menu groups, "Live feed" and the account, top to bottom (DOC-34 §4.2, prototype
 * shell-and-navigation.html).
 */
export function SidebarContent({
  access,
  items,
  counts,
  realtime,
  onOpenSearch,
  onShowShortcuts,
  collapsed = false,
  onToggleCollapsed,
  onNavigate,
}: SidebarProps) {
  const viewer = access.role !== undefined;
  return (
    <>
      <div className={cn('flex items-center gap-2.5 px-1 pt-0.5 pb-3.5', collapsed && 'flex-col px-0')}>
        <Link
          to={homePath(access) as '/'}
          onClick={onNavigate}
          aria-label={en.brand.home}
          className="flex min-w-0 flex-1 items-center gap-2.5 rounded-md"
        >
          <Logo />
          {collapsed ? null : (
            <span className="min-w-0">
              <span className="block text-sm leading-tight font-semibold tracking-[-0.02em]">{en.brand.name}</span>
              <span className="block truncate text-xs text-muted-foreground">{en.app.agency}</span>
            </span>
          )}
        </Link>
        {onToggleCollapsed ? (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={collapsed ? en.nav.expand : en.nav.collapse}
            aria-expanded={!collapsed}
            onClick={onToggleCollapsed}
          >
            {collapsed ? <PanelLeftOpen aria-hidden="true" /> : <PanelLeftClose aria-hidden="true" />}
          </Button>
        ) : null}
      </div>

      <button
        type="button"
        onClick={onOpenSearch}
        aria-label={collapsed ? en.search.open : undefined}
        aria-keyshortcuts="Meta+K Control+K"
        className={cn(
          'flex h-8.5 w-full items-center gap-2 rounded-md border border-border bg-card pr-2 pl-2.5 text-left text-sm text-muted-foreground shadow-xs hover:bg-muted',
          collapsed && 'justify-center px-0',
        )}
      >
        <Search className="size-4 shrink-0" aria-hidden="true" />
        {collapsed ? null : (
          <>
            <span>{en.search.label}</span>
            <kbd className="ml-auto rounded-[5px] border border-b-2 border-border bg-surface px-1.25 py-0.75 font-mono text-[10.5px] leading-none">
              {en.search.shortcut}
            </kbd>
          </>
        )}
      </button>

      <nav aria-label={en.nav.primary} className="mt-0.5 min-h-0 flex-1 overflow-y-auto">
        <NavList
          items={items}
          counts={counts}
          readOnly={access.role === 'viewer'}
          collapsed={collapsed}
          onNavigate={onNavigate}
        />
      </nav>

      <div className="mt-3">
        <LiveFeedCard realtime={realtime} showRate={viewer} collapsed={collapsed} />
      </div>
      <div className={cn('px-1 pt-3', collapsed && 'flex justify-center px-0')}>
        <AccountMenu access={access} onShowShortcuts={onShowShortcuts} layout={collapsed ? 'rail' : 'full'} />
      </div>
    </>
  );
}

/** The desktop sidebar (≥ 1024 px): 232 px, or a 64 px icon rail when collapsed (1024–1279 px only). */
export function AppSidebar(props: SidebarProps) {
  const { collapsed = false } = props;
  return (
    <aside
      data-collapsed={collapsed}
      className={cn(
        'sticky top-0 flex h-svh shrink-0 flex-col border-r border-border bg-surface pt-3.5 pb-3',
        collapsed ? 'w-16 px-2' : 'w-58 px-3',
        props.className,
      )}
    >
      <SidebarContent {...props} />
    </aside>
  );
}
