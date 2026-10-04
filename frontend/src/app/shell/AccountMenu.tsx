import { Ellipsis, Keyboard, LogIn, LogOut, Monitor, Moon, Sun } from 'lucide-react';

import type { Access } from '@/app/access';
import { useSession } from '@/app/session';
import { useTheme, type ThemeChoice } from '@/app/theme-provider';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Skeleton } from '@/components/ui/skeleton';
import { en } from '@/i18n/en';
import { initials } from '@/lib/format';
import { cn } from '@/lib/utils';

const THEMES: { value: ThemeChoice; icon: typeof Sun }[] = [
  { value: 'light', icon: Sun },
  { value: 'dark', icon: Moon },
  { value: 'system', icon: Monitor },
];

function displayNameOf(access: Access, fallback?: string): string {
  return access.me?.displayName ?? fallback ?? access.me?.username ?? '';
}

function roleKey(access: Access): 'operator' | 'viewer' | 'none' {
  return access.role ?? 'none';
}

export function Avatar({ name, className }: { name: string; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'grid size-7 shrink-0 place-items-center rounded-full bg-tone-teal-solid text-[11px] font-semibold text-white',
        className,
      )}
    >
      {initials(name)}
    </span>
  );
}

function ThemeItems() {
  const { theme, setTheme } = useTheme();
  return (
    <>
      <DropdownMenuLabel>{en.account.theme}</DropdownMenuLabel>
      <DropdownMenuRadioGroup
        value={theme}
        onValueChange={(value) => {
          setTheme(value as ThemeChoice);
        }}
      >
        {THEMES.map(({ value, icon: Icon }) => (
          <DropdownMenuRadioItem key={value} value={value}>
            <Icon aria-hidden="true" />
            {en.theme[value]}
          </DropdownMenuRadioItem>
        ))}
      </DropdownMenuRadioGroup>
    </>
  );
}

interface AccountMenuProps {
  access: Access;
  onShowShortcuts: () => void;
  /**
   * `full`: foot of the sidebar. `rail`: the 64 px icon rail, icons only. `bar`: the mobile top bar, where the theme
   * lives in "More" or the menu sheet instead.
   */
  layout?: 'full' | 'rail' | 'bar';
}

/**
 * Foot of the sidebar (DOC-34 §4.2): avatar, name and role with the account menu; "Sign in" and a theme button when
 * anonymous; a skeleton while the session is restored (DOC-37 §2.8).
 */
export function AccountMenu({ access, onShowShortcuts, layout = 'full' }: AccountMenuProps) {
  const compact = layout !== 'full';
  const session = useSession();
  const { resolved } = useTheme();

  if (access.pending) {
    return (
      <div aria-busy="true" className="flex items-center gap-2.5" data-testid="account-skeleton">
        <Skeleton className="size-7 rounded-full" />
        {compact ? null : (
          <div className="flex flex-1 flex-col gap-1.5">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-2.5 w-16" />
          </div>
        )}
      </div>
    );
  }

  if (!access.signedIn) {
    const ThemeIcon = resolved === 'dark' ? Moon : Sun;
    const signIn = session.available ? (
      layout === 'rail' ? (
        <Button size="icon" aria-label={en.account.signIn} onClick={() => void session.signIn()}>
          <LogIn aria-hidden="true" />
        </Button>
      ) : (
        <Button
          size={layout === 'bar' ? 'sm' : 'default'}
          className={cn(layout === 'full' && 'flex-1')}
          onClick={() => void session.signIn()}
        >
          {en.account.signIn}
        </Button>
      )
    ) : null;
    if (layout === 'bar') return signIn;
    return (
      <div className={cn('flex items-center gap-2', layout === 'rail' && 'flex-col')}>
        {signIn}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label={en.account.theme}>
              <ThemeIcon aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" side={layout === 'rail' ? 'right' : 'top'}>
            <ThemeItems />
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    );
  }

  const name = displayNameOf(access, session.displayName);
  const role = roleKey(access);
  const menu = (
    <DropdownMenuContent
      align={layout === 'full' ? 'start' : 'end'}
      side={layout === 'bar' ? 'bottom' : layout === 'rail' ? 'right' : 'top'}
    >
      <DropdownMenuLabel className="text-sm font-normal text-foreground">
        {en.account.signedInAs(name)}
      </DropdownMenuLabel>
      <DropdownMenuLabel className="pt-0 font-normal">{en.account.role(en.account.roles[role])}</DropdownMenuLabel>
      <DropdownMenuSeparator />
      <ThemeItems />
      <DropdownMenuSeparator />
      <DropdownMenuItem onSelect={onShowShortcuts}>
        <Keyboard aria-hidden="true" />
        {en.account.shortcuts}
      </DropdownMenuItem>
      <DropdownMenuItem onSelect={() => void session.signOut()}>
        <LogOut aria-hidden="true" />
        {en.account.signOut}
      </DropdownMenuItem>
    </DropdownMenuContent>
  );

  if (compact) {
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button type="button" aria-label={en.account.menu} className="rounded-full">
            <Avatar name={name} />
          </button>
        </DropdownMenuTrigger>
        {menu}
      </DropdownMenu>
    );
  }

  return (
    <div className="flex items-center gap-2.5">
      <Avatar name={name} />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm leading-tight font-medium">{name}</p>
        <p className="truncate text-xs text-muted-foreground">{en.account.roleLine[role]}</p>
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" aria-label={en.account.menu}>
            <Ellipsis aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        {menu}
      </DropdownMenu>
    </div>
  );
}
