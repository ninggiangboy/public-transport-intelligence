import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { catalogCopy } from '@/i18n/catalog';
import { en } from '@/i18n/en';
import { renderRoute } from '@/test/render';

describe('/_ui design-system catalogue (DOC-35 §10)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('is served at the literal URL /_ui in development', async () => {
    const { router } = await renderRoute('/_ui');
    expect(router.state.location.pathname).toBe('/_ui');
    expect(await screen.findByRole('heading', { level: 1, name: catalogCopy.cover.title })).toBeInTheDocument();
  });

  it('shows every component in light and dark, side by side', async () => {
    const { container } = await renderRoute('/_ui');
    await screen.findByRole('heading', { level: 1, name: catalogCopy.cover.title });
    // The page is big: count in its text once instead of querying the tree for each string.
    const text = container.textContent;
    const count = (needle: string) => text.split(needle).length - 1;
    // Every severity, every confidence level and every Problem slug with copy appears once per theme.
    expect(count('Needs attention')).toBeGreaterThanOrEqual(2);
    expect(count('Schedule only')).toBe(2);
    // A block names the panel that failed for network and server errors; every other slug shows its own title.
    const named = ['internal-error', 'service-unavailable'];
    for (const [slug, copy] of Object.entries(en.error.slug)) {
      if (named.includes(slug)) continue;
      const title = copy.title({ thing: catalogCopy.sample.pageTitle });
      expect(count(title), title).toBeGreaterThanOrEqual(2);
    }
    expect(count(en.error.couldntLoad(catalogCopy.sample.pageTitle))).toBeGreaterThanOrEqual(6);
    expect(container.querySelectorAll('h3').length).toBeGreaterThan(15);
  }, 30_000);

  it('answers 404 when neither the dev server nor VITE_PTI_UI_CATALOG allows it', async () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_PTI_UI_CATALOG', '');
    await renderRoute('/_ui');
    expect(await screen.findByRole('heading', { name: en.notFound.title })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: catalogCopy.cover.title })).not.toBeInTheDocument();
  });

  it('is available in a production build made with VITE_PTI_UI_CATALOG=true', async () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_PTI_UI_CATALOG', 'true');
    await renderRoute('/_ui');
    expect(await screen.findByRole('heading', { level: 1, name: catalogCopy.cover.title })).toBeInTheDocument();
  });
});
