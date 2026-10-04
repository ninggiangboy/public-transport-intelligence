import type { Access } from '@/app/access';
import { SidebarContent, type SidebarProps } from '@/app/shell/AppSidebar';
import { NavList } from '@/app/shell/NavList';
import type { NavItem } from '@/app/shell/nav-items';
import type { NavCounts } from '@/app/shell/use-nav-counts';
import { useSession } from '@/app/session';
import { useTheme, type ThemeChoice } from '@/app/theme-provider';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { en } from '@/i18n/en';

// The sheets of narrow screens, loaded on first opening so that Radix Dialog stays out of the initial bundle
// (DOC-34 §7).

const THEMES: ThemeChoice[] = ['light', 'dark', 'system'];

interface SheetState {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** 768–1023 px: the sidebar in a sheet from the left. */
export function MenuSheet({ open, onOpenChange, sidebar }: SheetState & { sidebar: SidebarProps }) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="left" className="px-3 pt-3.5 pb-3">
        <SheetTitle className="sr-only">{en.nav.menuTitle}</SheetTitle>
        <SheetDescription className="sr-only">{en.nav.primary}</SheetDescription>
        <SidebarContent
          {...sidebar}
          onNavigate={() => {
            onOpenChange(false);
          }}
        />
      </SheetContent>
    </Sheet>
  );
}

interface MoreSheetProps extends SheetState {
  access: Access;
  items: readonly NavItem[];
  counts: NavCounts;
  /** The items the tabs do not show. */
  inMore: (item: NavItem) => boolean;
}

/** Under 768 px: Overview, Scorecard, Operations, theme and sign in/out (DOC-34 §4.2). */
export function MoreSheet({ open, onOpenChange, access, items, counts, inMore }: MoreSheetProps) {
  const session = useSession();
  const { theme, setTheme } = useTheme();
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" className="px-3 pt-2 pb-4">
        <SheetTitle className="px-2.5 pt-2 text-panel font-semibold">{en.nav.moreTitle}</SheetTitle>
        <SheetDescription className="sr-only">{en.nav.primary}</SheetDescription>
        <NavList
          items={items}
          counts={counts}
          readOnly={access.role === 'viewer'}
          only={inMore}
          onNavigate={() => {
            onOpenChange(false);
          }}
        />
        <div className="mt-4 flex flex-col gap-3 border-t border-border px-1 pt-4">
          <SegmentedControl
            label={en.account.theme}
            value={theme}
            onChange={setTheme}
            options={THEMES.map((value) => ({ value, label: en.theme[value] }))}
          />
          {access.pending ? null : access.signedIn ? (
            <Button variant="outline" onClick={() => void session.signOut()}>
              {en.account.signOut}
            </Button>
          ) : session.available ? (
            <Button onClick={() => void session.signIn()}>{en.account.signIn}</Button>
          ) : null}
        </div>
      </SheetContent>
    </Sheet>
  );
}
