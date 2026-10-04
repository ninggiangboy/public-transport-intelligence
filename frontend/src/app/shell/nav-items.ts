import {
  Activity,
  Bell,
  ChartColumn,
  CirclePlay,
  Inbox,
  LayoutGrid,
  Map as MapIcon,
  MapPin,
  RotateCcw,
  SlidersHorizontal,
  Ticket,
  type LucideIcon,
} from 'lucide-react';

import { hasRole, type Access } from '@/app/access';
import type { AppEnv } from '@/env';
import type { en } from '@/i18n/en';

export type NavGroupId = 'network' | 'analytics' | 'operations';
export type NavItemId = keyof typeof en.nav.items;

export interface NavItem {
  id: NavItemId;
  group: NavGroupId;
  to: string;
  icon: LucideIcon;
  /** `undefined`: everyone, anonymous included. */
  role?: 'viewer' | 'operator';
  /** Further paths the item is current on, e.g. batch lineage under Pipeline. */
  alsoActiveOn?: string[];
}

/** The main navigation of DOC-34 §4.1, in order. */
export const NAV_ITEMS: readonly NavItem[] = [
  { id: 'overview', group: 'network', to: '/overview', icon: LayoutGrid, role: 'viewer' },
  { id: 'map', group: 'network', to: '/map', icon: MapIcon },
  { id: 'stops', group: 'network', to: '/stops', icon: MapPin },
  { id: 'alerts', group: 'network', to: '/alerts', icon: Bell },
  { id: 'scorecard', group: 'analytics', to: '/scorecard', icon: ChartColumn, role: 'viewer' },
  {
    id: 'pipeline',
    group: 'operations',
    to: '/ops/jobs',
    icon: Activity,
    role: 'viewer',
    alsoActiveOn: ['/ops/batches'],
  },
  { id: 'deadLetters', group: 'operations', to: '/ops/dlq', icon: Inbox, role: 'viewer' },
  { id: 'replay', group: 'operations', to: '/ops/replay', icon: RotateCcw, role: 'viewer' },
  { id: 'ticketing', group: 'operations', to: '/ops/ticketing', icon: Ticket, role: 'viewer' },
  { id: 'controls', group: 'operations', to: '/ops/controls', icon: SlidersHorizontal, role: 'viewer' },
  { id: 'demo', group: 'operations', to: '/ops/demo', icon: CirclePlay, role: 'operator' },
];

export const NAV_GROUPS: readonly NavGroupId[] = ['network', 'analytics', 'operations'];

/** The items this user may open. Demo also needs `demoControl` in env.js (DOC-34 §10). */
export function visibleItems(access: Pick<Access, 'role'>, appEnv: Pick<AppEnv, 'demoControl'>): NavItem[] {
  return NAV_ITEMS.filter((item) => {
    if (item.id === 'demo' && !appEnv.demoControl) return false;
    return item.role === undefined || hasRole(access, item.role);
  });
}

export function isItemActive(item: NavItem, pathname: string): boolean {
  return [item.to, ...(item.alsoActiveOn ?? [])].some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

/** Logo target: the default page of the role (DOC-34 §5.2). */
export function homePath(access: Pick<Access, 'role'>): string {
  return access.role ? '/overview' : '/map';
}
