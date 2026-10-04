import { useSyncExternalStore } from 'react';

import type { Palette } from '@/features/map/model';

/** Reads the colours of the current theme from the tokens on <html> (tokens.css). */
export function readPalette(element: Element = document.documentElement): Palette {
  const style = getComputedStyle(element);
  const token = (name: string) => style.getPropertyValue(name).trim();
  return {
    delay: {
      early: token('--delay-early'),
      'on-time': token('--delay-on-time'),
      late: token('--delay-late'),
      'very-late': token('--delay-very-late'),
      unknown: token('--delay-unknown'),
    },
    crowding: {
      success: token('--tone-success-solid'),
      teal: token('--tone-teal-solid'),
      warning: token('--tone-warning-solid'),
      danger: token('--tone-danger-solid'),
      unknown: token('--delay-unknown'),
    },
    primary: token('--primary'),
    bunching: token('--bunching'),
    foreground: token('--foreground'),
    card: token('--card'),
    land: token('--map-land'),
  };
}

let cached: { theme: string; palette: Palette } | undefined;

function snapshot(): Palette {
  const theme = document.documentElement.className;
  if (cached?.theme !== theme) cached = { theme, palette: readPalette() };
  return cached.palette;
}

function subscribe(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  return () => {
    observer.disconnect();
  };
}

/** The palette of the theme on <html>, read again when the theme provider switches its class. */
export function usePalette(): Palette {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
