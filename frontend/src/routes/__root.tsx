import { createRootRouteWithContext } from '@tanstack/react-router';

import { AppShell } from '@/app/shell/AppShell';
import { NotFoundPage } from '@/app/shell/NotFoundPage';
import type { RouterContext } from '@/router';

export const Route = createRootRouteWithContext<RouterContext>()({
  component: AppShell,
  notFoundComponent: NotFoundPage,
});
