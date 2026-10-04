import { Keyboard, LogOut, Monitor, Moon, Sun } from 'lucide-react';
import type { ReactElement } from 'react';

import { useTheme, type ThemeChoice } from '@/app/theme-provider';
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
import { en } from '@/i18n/en';

// The account and theme menus, loaded on first use so that Radix Menu stays out of the initial bundle (DOC-34 §7).
// AccountMenu renders the same trigger until this module arrives, then this one opens straight away.

const THEMES: { value: ThemeChoice; icon: typeof Sun }[] = [
  { value: 'light', icon: Sun },
  { value: 'dark', icon: Moon },
  { value: 'system', icon: Monitor },
];

export interface MenuPlacement {
  side: 'top' | 'right' | 'bottom';
  align: 'start' | 'end';
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

interface ThemeMenuProps extends MenuPlacement {
  trigger: ReactElement;
}

/** Theme only, for anonymous users. */
export function ThemeMenu({ trigger, side, align }: ThemeMenuProps) {
  return (
    <DropdownMenu defaultOpen>
      <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
      <DropdownMenuContent side={side} align={align}>
        <ThemeItems />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

interface UserMenuProps extends MenuPlacement {
  trigger: ReactElement;
  name: string;
  roleLabel: string;
  onShowShortcuts: () => void;
  onSignOut: () => void;
}

/** "Signed in as", role, theme, keyboard shortcuts and sign out (screens/shell-and-navigation §4). */
export function UserMenu({ trigger, side, align, name, roleLabel, onShowShortcuts, onSignOut }: UserMenuProps) {
  return (
    <DropdownMenu defaultOpen>
      <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
      <DropdownMenuContent side={side} align={align}>
        <DropdownMenuLabel className="text-sm font-normal text-foreground">
          {en.account.signedInAs(name)}
        </DropdownMenuLabel>
        <DropdownMenuLabel className="pt-0 font-normal">{en.account.role(roleLabel)}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <ThemeItems />
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onShowShortcuts}>
          <Keyboard aria-hidden="true" />
          {en.account.shortcuts}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={onSignOut}>
          <LogOut aria-hidden="true" />
          {en.account.signOut}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
