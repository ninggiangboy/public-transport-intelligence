import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { useState } from 'react';

import { ThemeProvider } from '@/app/theme-provider';
import { createQueryClient } from '@/app/query-client';
import { RealtimeProvider } from '@/realtime/RealtimeProvider';
import { createAppRouter } from '@/router';

// DOC-34 §9.2 nests Theme → Auth → Query → Freshness → Realtime → Router; Auth and Freshness join with the shell
// (P5-04).
export function AppProviders() {
  const [queryClient] = useState(createQueryClient);
  const [router] = useState(() => createAppRouter({ queryClient }));
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <RealtimeProvider>
          <RouterProvider router={router} />
        </RealtimeProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
