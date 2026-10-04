import { Link, useRouterState } from '@tanstack/react-router';

import { NAV_GROUPS, isItemActive, type NavGroupId, type NavItem } from '@/app/shell/nav-items';
import type { NavCounts } from '@/app/shell/use-nav-counts';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';

interface Marker {
  /** Shown text. */
  text?: string;
  /** Screen-reader text. */
  label: string;
  kind: 'count' | 'hot' | 'dot-danger' | 'paused';
}

function markerOf(item: NavItem, counts: NavCounts): Marker | undefined {
  const count = (n: number | undefined, label: (n: number) => string, kind: Marker['kind'] = 'count') =>
    n === undefined || n === 0 ? undefined : { text: en.nav.count(n), label: label(n), kind };
  switch (item.id) {
    case 'alerts':
      return count(counts.unacknowledgedAlerts, en.nav.unacknowledgedAlerts, 'hot');
    case 'deadLetters':
      return count(counts.openDeadLetters, en.nav.openDeadLetters);
    case 'replay':
      return count(counts.runningReplays, en.nav.runningReplays);
    case 'ticketing':
      return count(counts.ticketingAnomalies, en.nav.ticketingAnomalies);
    case 'pipeline':
      return counts.pipelineFailed ? { label: en.nav.pipelineFailed, kind: 'dot-danger' } : undefined;
    case 'controls':
      return counts.paused ? { text: en.nav.paused, label: en.nav.paused, kind: 'paused' } : undefined;
    default:
      return undefined;
  }
}

function MarkerView({ marker }: { marker: Marker }) {
  if (marker.kind === 'dot-danger') {
    return (
      <span className="ml-auto flex items-center">
        <span aria-hidden="true" className="size-[7px] rounded-full bg-tone-danger-solid" />
        <span className="sr-only">{marker.label}</span>
      </span>
    );
  }
  if (marker.kind === 'paused') {
    return (
      <span className="ml-auto inline-flex h-4.5 items-center gap-1 rounded-full bg-tone-warning-bg px-1.5 text-[11px] font-medium text-tone-warning-fg">
        <span aria-hidden="true" className="size-1.5 rounded-full bg-tone-warning-solid" />
        {marker.text}
      </span>
    );
  }
  return (
    <span
      className={cn(
        'ml-auto text-[11.5px] font-medium tabular-nums',
        marker.kind === 'hot'
          ? 'inline-flex h-4.5 items-center rounded-full bg-tone-danger-bg px-1.5 text-tone-danger-fg'
          : 'text-muted-foreground',
      )}
    >
      <span aria-hidden="true">{marker.text}</span>
      <span className="sr-only">{marker.label}</span>
    </span>
  );
}

interface NavListProps {
  items: readonly NavItem[];
  counts: NavCounts;
  /** Viewer: "Read-only" next to the Operations heading (DOC-34 §3). */
  readOnly: boolean;
  /** Icon rail: labels move to tooltips, headings stay for screen readers only. */
  collapsed?: boolean;
  /** Groups to leave out (the bottom tabs already show the Network items on phones). */
  only?: (item: NavItem) => boolean;
  onNavigate?: () => void;
}

/** The three groups of DOC-34 §4.1; the current item is a white card with an indigo icon and `aria-current`. */
export function NavList({ items, counts, readOnly, collapsed = false, only, onNavigate }: NavListProps) {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const groups = NAV_GROUPS.map((group) => ({
    group,
    items: items.filter((item) => item.group === group && (only?.(item) ?? true)),
  })).filter(({ items: groupItems }) => groupItems.length > 0);

  return (
    <>
      {groups.map(({ group, items: groupItems }) => (
        <div key={group}>
          <GroupHeading group={group} readOnly={readOnly} collapsed={collapsed} />
          <ul>
            {groupItems.map((item) => {
              const active = isItemActive(item, pathname);
              const marker = markerOf(item, counts);
              const label = en.nav.items[item.id];
              const link = (
                <Link
                  to={item.to as '/'}
                  onClick={onNavigate}
                  aria-current={active ? 'page' : undefined}
                  aria-label={collapsed ? (marker ? `${label}, ${marker.label}` : label) : undefined}
                  className={cn(
                    'relative mb-px flex h-8.5 items-center gap-2.5 rounded-lg border border-transparent px-2.5 text-nav font-medium text-foreground-2 hover:bg-muted focus-visible:outline-2 focus-visible:outline-ring',
                    active && 'border-border bg-card text-foreground shadow-xs hover:bg-card',
                    collapsed && 'justify-center px-0',
                  )}
                >
                  <item.icon
                    className={cn('size-4.25 shrink-0', active ? 'text-primary' : 'text-muted-foreground')}
                    strokeWidth={1.75}
                    aria-hidden="true"
                  />
                  {collapsed ? (
                    marker && marker.kind !== 'count' ? (
                      <span
                        aria-hidden="true"
                        className={cn(
                          'absolute top-1.5 right-2.5 size-1.5 rounded-full',
                          marker.kind === 'paused' ? 'bg-tone-warning-solid' : 'bg-tone-danger-solid',
                        )}
                      />
                    ) : null
                  ) : (
                    <>
                      <span className="truncate">{label}</span>
                      {marker ? <MarkerView marker={marker} /> : null}
                    </>
                  )}
                </Link>
              );
              return (
                <li key={item.id}>
                  {collapsed ? (
                    <Tooltip>
                      <TooltipTrigger asChild>{link}</TooltipTrigger>
                      <TooltipContent side="right">{label}</TooltipContent>
                    </Tooltip>
                  ) : (
                    link
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </>
  );
}

function GroupHeading({ group, readOnly, collapsed }: { group: NavGroupId; readOnly: boolean; collapsed: boolean }) {
  const title = en.nav.groups[group];
  if (collapsed) {
    return <h2 className="sr-only">{title}</h2>;
  }
  return (
    <div className="flex items-center justify-between px-2.5 pt-4 pr-1.5 pb-1.5">
      <h2 className="text-[11.5px] font-medium text-muted-foreground">{title}</h2>
      {group === 'operations' && readOnly ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <span
              tabIndex={0}
              className="inline-flex h-4.5 items-center rounded-sm border border-tone-neutral-border bg-tone-neutral-bg px-1.5 text-[11px] font-medium text-tone-neutral-fg"
            >
              {en.nav.readOnly}
            </span>
          </TooltipTrigger>
          <TooltipContent>{en.nav.readOnlyHelp}</TooltipContent>
        </Tooltip>
      ) : null}
    </div>
  );
}
