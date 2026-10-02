import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { useState } from 'react';

import { createQueryClient } from '@/app/query-client';
import { createAppRouter } from '@/router';

// DOC-34 §9.2 nests Theme → Auth → Query → Freshness → Realtime → Router; the providers other than Query join in
// P5-03…P5-05.
export function AppProviders() {
  const [queryClient] = useState(createQueryClient);
  const [router] = useState(() => createAppRouter({ queryClient }));
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
