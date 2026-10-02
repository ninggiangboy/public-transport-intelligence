import { vi } from 'vitest';

type Listener = (event: MediaQueryListEvent) => void;

/**
 * jsdom has no `matchMedia`. This installs one whose queries answer from `matches` (a query string -> boolean map) and
 * returns `set(query, value)`, which flips a query and notifies its listeners.
 */
export function mockMatchMedia(matches: Record<string, boolean> = {}) {
  const state = new Map(Object.entries(matches));
  const listeners = new Map<string, Set<Listener>>();
  vi.stubGlobal(
    'matchMedia',
    (query: string): MediaQueryList =>
      ({
        media: query,
        get matches() {
          return state.get(query) ?? false;
        },
        addEventListener: (_type: string, listener: Listener) => {
          const set = listeners.get(query) ?? new Set<Listener>();
          set.add(listener);
          listeners.set(query, set);
        },
        removeEventListener: (_type: string, listener: Listener) => {
          listeners.get(query)?.delete(listener);
        },
      }) as unknown as MediaQueryList,
  );
  return {
    set(query: string, value: boolean) {
      state.set(query, value);
      for (const listener of listeners.get(query) ?? [])
        listener({ matches: value, media: query } as MediaQueryListEvent);
    },
    listenerCount: (query: string) => listeners.get(query)?.size ?? 0,
  };
}

export const DARK_QUERY = '(prefers-color-scheme: dark)';
export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';
