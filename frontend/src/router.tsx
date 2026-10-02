import type { QueryClient } from '@tanstack/react-query';
import { createRouter, type RouterHistory } from '@tanstack/react-router';

import { parseSearch, stringifySearch } from '@/lib/url';
import { routeTree } from '@/routeTree.gen';

export interface RouterContext {
  queryClient: QueryClient;
}

export function createAppRouter(context: RouterContext, history?: RouterHistory) {
  return createRouter({
    routeTree,
    context,
    ...(history ? { history } : {}),
    parseSearch,
    stringifySearch,
    defaultPreload: 'intent',
    // TanStack Query owns server data (ADR-0020); loaders only prefetch into it, so the router cache stays out of the way.
    defaultPreloadStaleTime: 0,
    scrollRestoration: true,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
