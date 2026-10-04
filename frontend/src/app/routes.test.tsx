import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { en } from '@/i18n/en';
import { renderRoute } from '@/test/render';

describe('routes', () => {
  it('renders the 404 page inside the shell for an unknown URL', async () => {
    await renderRoute('/no/such/page');
    expect(await screen.findByRole('heading', { level: 1, name: en.notFound.title })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: en.notFound.action })).toHaveAttribute('href', '/map');
    expect(screen.getByRole('navigation', { name: en.nav.primary })).toBeInTheDocument();
  });

  it('sets the document title of a page', async () => {
    await renderRoute('/alerts');
    await waitFor(() => {
      expect(document.title).toBe(en.page.title(en.nav.items.alerts));
    });
  });

  it('completes the sign-in on /auth/callback and returns to the page the user came from (AC-4)', async () => {
    const { router } = await renderRoute('/auth/callback?code=abc&state=xyz', {
      session: { completeSignIn: () => Promise.resolve('/ops/dlq?severity=2') },
    });
    await waitFor(() => {
      expect(router.state.location.href).toBe('/ops/dlq?severity=2');
    });
  });

  it('shows the callback error when the sign-in does not complete', async () => {
    await renderRoute('/auth/callback?code=abc&state=bad', {
      session: { completeSignIn: () => Promise.reject(new Error('No matching state')) },
    });
    expect(await screen.findByRole('heading', { name: en.auth.callbackFailedTitle })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.auth.tryAgain })).toBeInTheDocument();
  });
});
