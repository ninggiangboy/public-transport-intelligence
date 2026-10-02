import { keepPreviousData, QueryClient } from '@tanstack/react-query';

const MAX_RETRIES = 2;

/** Defaults of DOC-34 §9.2: retry network errors and 5xx at most twice, never 4xx. */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        gcTime: 5 * 60_000,
        // Safety net for missed SSE events while the page is visible (DR-42).
        refetchInterval: 60_000,
        refetchIntervalInBackground: false,
        refetchOnWindowFocus: true,
        placeholderData: keepPreviousData,
        retry: (failureCount, error) => failureCount < MAX_RETRIES && isRetryable(error),
      },
    },
  });
}

function isRetryable(error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status !== 'number' || status >= 500;
}
