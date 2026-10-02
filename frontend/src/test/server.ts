import { setupServer } from 'msw/node';

import { handlers } from '@/test/handlers';

/** MSW for Vitest; a request without a handler fails the test (setup.ts). */
export const server = setupServer(...handlers);
