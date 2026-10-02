import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';

/** The choice of the user; `system` follows `prefers-color-scheme` (DOC-35 §9). */
export type ThemeChoice = 'light' | 'dark' | 'system';

export const THEME_STORAGE_KEY = 'pti.theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

function isThemeChoice(value: unknown): value is ThemeChoice {
  return value === 'light' || value === 'dark' || value === 'system';
}

// localStorage can be missing or throw (private windows, blocked site data), so every access is guarded (DOC-34 §9.2).
function readStoredChoice(): ThemeChoice {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return isThemeChoice(stored) ? stored : 'system';
  } catch {
    return 'system';
  }
}

function writeStoredChoice(choice: ThemeChoice) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, choice);
  } catch {
    // The choice then lasts for this session only.
  }
}

function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches;
}

function subscribeToSystem(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => undefined;
  const query = window.matchMedia(DARK_QUERY);
  query.addEventListener('change', onChange);
  return () => {
    query.removeEventListener('change', onChange);
  };
}

interface ThemeContextValue {
  /** What the user picked. */
  theme: ThemeChoice;
  /** What is applied: `system` resolved to `light` or `dark`. */
  resolved: 'light' | 'dark';
  setTheme: (theme: ThemeChoice) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/** Outermost provider (DOC-34 §9.2 item 1): keeps the `dark` class of `<html>` in step with the choice. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeChoice>(readStoredChoice);
  const systemDark = useSyncExternalStore(subscribeToSystem, systemPrefersDark, () => false);

  const resolved = theme === 'system' ? (systemDark ? 'dark' : 'light') : theme;

  // Layout effect: the class lands before the first paint, so a dark user does not see a light flash.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('dark', resolved === 'dark');
  }, [resolved]);

  const setTheme = useCallback((next: ThemeChoice) => {
    setThemeState(next);
    writeStoredChoice(next);
  }, []);

  const value = useMemo(() => ({ theme, resolved, setTheme }), [theme, resolved, setTheme]);
  return <ThemeContext value={value}>{children}</ThemeContext>;
}

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext);
  if (!value) throw new Error('useTheme needs a ThemeProvider');
  return value;
}
