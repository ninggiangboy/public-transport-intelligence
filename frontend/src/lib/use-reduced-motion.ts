import { useSyncExternalStore } from 'react';

const QUERY = '(prefers-reduced-motion: reduce)';

function subscribe(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => undefined;
  const query = window.matchMedia(QUERY);
  query.addEventListener('change', onChange);
  return () => {
    query.removeEventListener('change', onChange);
  };
}

function snapshot() {
  return typeof window.matchMedia === 'function' && window.matchMedia(QUERY).matches;
}

/**
 * True under `prefers-reduced-motion: reduce` (DOC-35 §4.4, DS-09). CSS already stops transitions and animations
 * (globals.css); components that spin an icon also read it, so the class is absent rather than overridden.
 */
export function useReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}
