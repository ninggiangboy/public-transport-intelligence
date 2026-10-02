import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { en } from '@/i18n/en';
import { renderRoute } from '@/test/render';

describe('routes', () => {
  it('renders the start page', async () => {
    await renderRoute('/');
    expect(await screen.findByRole('heading', { name: en.app.name })).toBeInTheDocument();
  });

  it('renders the 404 page for an unknown URL', async () => {
    await renderRoute('/no/such/page');
    expect(await screen.findByRole('heading', { name: en.notFound.title })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: en.notFound.action })).toHaveAttribute('href', '/');
  });
});
