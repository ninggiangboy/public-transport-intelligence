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
  /** What a route tells the shell about its layout (DOC-34 §4.2). */
  interface StaticDataRouteOption {
    /** Live map: content fills the area, no padding, no footer (attribution sits on the map). */
    fullBleed?: boolean;
    /** Pages without real-time or event-time data (Scorecard, Controls) show no StaleBanner (DOC-37 §2.4). */
    hideStaleBanner?: boolean;
    /** Pages rendered without the shell (the /_ui catalogue). */
    bare?: boolean;
  }
}
