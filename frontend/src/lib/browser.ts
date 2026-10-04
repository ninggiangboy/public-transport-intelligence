import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';

import { en } from '@/i18n/en';

// Browser state for the shell: connectivity, viewport and stored preferences. Storage can be missing or throw (private
// windows, blocked site data), so every access is guarded and a failure only loses the preference (DOC-34 §4.2).

function subscribeOnline(onChange: () => void) {
  window.addEventListener('online', onChange);
  window.addEventListener('offline', onChange);
  return () => {
    window.removeEventListener('online', onChange);
    window.removeEventListener('offline', onChange);
  };
}

/** `navigator.onLine`, kept current (DOC-37 §2.4 priority 1). */
export function useOnline(): boolean {
  return useSyncExternalStore(
    subscribeOnline,
    () => navigator.onLine,
    () => true,
  );
}

/** Whether a media query matches; `fallback` where `matchMedia` is missing (jsdom). */
export function useMediaQuery(query: string, fallback = false): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (typeof window.matchMedia !== 'function') return () => undefined;
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => {
        list.removeEventListener('change', onChange);
      };
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : fallback),
    () => fallback,
  );
}

type StorageArea = 'local' | 'session';

function storage(area: StorageArea): Storage {
  return area === 'local' ? window.localStorage : window.sessionStorage;
}

export function readStored(area: StorageArea, key: string): string | null {
  try {
    return storage(area).getItem(key);
  } catch {
    return null;
  }
}

export function writeStored(area: StorageArea, key: string, value: string) {
  try {
    storage(area).setItem(key, value);
  } catch {
    // The preference then lasts for this page only.
  }
}

/** A boolean preference in Web Storage, `initial` when nothing (readable) is stored. */
export function useStoredFlag(area: StorageArea, key: string, initial: boolean): [boolean, (value: boolean) => void] {
  const [value, setValue] = useState(() => {
    const stored = readStored(area, key);
    return stored === null ? initial : stored === 'true';
  });
  const set = useCallback(
    (next: boolean) => {
      setValue(next);
      writeStored(area, key, String(next));
    },
    [area, key],
  );
  return [value, set];
}

/** `document.title` as "{Page} — PTI" (DOC-37 §6). */
export function useDocumentTitle(page: string | undefined) {
  useEffect(() => {
    document.title = page ? en.page.title(page) : en.app.name;
  }, [page]);
}
