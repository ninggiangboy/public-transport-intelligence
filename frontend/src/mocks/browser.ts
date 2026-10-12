import { setupWorker } from 'msw/browser';

import { handlers } from '@/test/handlers';
import { simHandlers, simRun, simStatus } from '@/test/sim';

/**
 * `pnpm dev:mock`: the dev server answers /api from the openapi.json examples, without a backend (DOC-44 §10). The
 * simulator proxy, which openapi.json leaves out, answers with the examples of src/test/sim.ts and one running run.
 */
export async function startMockApi() {
  const running = simRun();
  const sim = simHandlers({
    status: {
      ...simStatus,
      runningScenarios: [{ runId: running.runId, scenario: running.scenario, plannedEndAt: running.plannedEndAt }],
    },
    runs: [running, simRun({ runId: 'r-done', status: 'COMPLETED', endedAt: running.startedAt })],
  });
  await setupWorker(...sim, ...handlers).start({ onUnhandledRequest: 'bypass', quiet: false });
}
