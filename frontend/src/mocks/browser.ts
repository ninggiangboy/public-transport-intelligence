import { setupWorker } from 'msw/browser';

import { handlers } from '@/test/handlers';

/** `pnpm dev:mock`: the dev server answers /api from the openapi.json examples, without a backend (DOC-44 §10). */
export async function startMockApi() {
  await setupWorker(...handlers).start({ onUnhandledRequest: 'bypass', quiet: false });
}
