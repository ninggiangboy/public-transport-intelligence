import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { THEME_STORAGE_KEY, ThemeProvider, useTheme } from '@/app/theme-provider';
import { DARK_QUERY, mockMatchMedia } from '@/test/media';

function Probe() {
  const { theme, resolved, setTheme } = useTheme();
  return (
    <div>
      <p>{`theme:${theme} resolved:${resolved}`}</p>
      <button
        type="button"
        onClick={() => {
          setTheme('dark');
        }}
      >
        dark
      </button>
      <button
        type="button"
        onClick={() => {
          setTheme('light');
        }}
      >
        light
      </button>
      <button
        type="button"
        onClick={() => {
          setTheme('system');
        }}
      >
        system
      </button>
    </div>
  );
}

const isDark = () => document.documentElement.classList.contains('dark');

describe('ThemeProvider (DOC-34 §9.2)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('follows the system when nothing is stored and reacts to a change of it', () => {
    const media = mockMatchMedia({ [DARK_QUERY]: false });
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByText('theme:system resolved:light')).toBeInTheDocument();
    expect(isDark()).toBe(false);

    act(() => {
      media.set(DARK_QUERY, true);
    });
    expect(screen.getByText('theme:system resolved:dark')).toBeInTheDocument();
    expect(isDark()).toBe(true);
  });

  it('persists the choice under pti.theme and toggles the dark class on <html>', async () => {
    mockMatchMedia({ [DARK_QUERY]: false });
    const user = userEvent.setup();
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'dark' }));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(isDark()).toBe(true);

    await user.click(screen.getByRole('button', { name: 'light' }));
    expect(window.localStorage.getItem('pti.theme')).toBe('light');
    expect(isDark()).toBe(false);
  });

  it('starts from the stored choice, ignoring a stored value it does not know', () => {
    mockMatchMedia({ [DARK_QUERY]: false });
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    const { unmount } = render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByText('theme:dark resolved:dark')).toBeInTheDocument();
    unmount();

    window.localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByText('theme:system resolved:light')).toBeInTheDocument();
  });

  it('keeps working when localStorage throws', async () => {
    mockMatchMedia({ [DARK_QUERY]: true });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });
    const user = userEvent.setup();
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    // The read fell back to `system`, which is dark here.
    expect(screen.getByText('theme:system resolved:dark')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'light' }));
    expect(screen.getByText('theme:light resolved:light')).toBeInTheDocument();
    expect(isDark()).toBe(false);
  });

  it('works without matchMedia', () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByText('theme:system resolved:light')).toBeInTheDocument();
  });

  it('refuses useTheme outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Probe />)).toThrow(/ThemeProvider/);
  });
});
