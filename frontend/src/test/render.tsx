import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { render } from '@testing-library/react';

import { createQueryClient } from '@/app/query-client';
import { ThemeProvider } from '@/app/theme-provider';
import { createAppRouter } from '@/router';

/** Renders the whole app at `path` with an in-memory history; screens are tested through their routes (DOC-44 §10). */
export async function renderRoute(path: string) {
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } });
  const router = createAppRouter({ queryClient }, createMemoryHistory({ initialEntries: [path] }));
  await router.load();
  const result = render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
  return { ...result, router, queryClient };
}
