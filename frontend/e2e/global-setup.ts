import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Records `stackStart`, the moment the compose stack was up (`make smoke` passed), in e2e/.state.json. The cases of the
// `late` project wait until the raw zone holds data older than the replay settle time (DOC-36 screens/ops-console-replay
// §10). E2E_STACK_START (ISO-8601) gives the moment explicitly; otherwise a state written in the last 6 hours is kept,
// so that a rerun against the same stack does not wait again, and a new state starts now.

const STATE = fileURLToPath(new URL('./.state.json', import.meta.url));
const KEEP_MS = 6 * 3_600_000;

export interface E2eState {
  stackStart: string;
}

export function readState(): E2eState | undefined {
  if (!existsSync(STATE)) return undefined;
  try {
    const state = JSON.parse(readFileSync(STATE, 'utf8')) as Partial<E2eState>;
    return typeof state.stackStart === 'string' && !Number.isNaN(Date.parse(state.stackStart))
      ? { stackStart: state.stackStart }
      : undefined;
  } catch {
    return undefined;
  }
}

export default function globalSetup() {
  const given = process.env.E2E_STACK_START;
  const kept = readState();
  const stackStart =
    given && !Number.isNaN(Date.parse(given))
      ? new Date(given).toISOString()
      : kept && Date.now() - Date.parse(kept.stackStart) < KEEP_MS
        ? kept.stackStart
        : new Date().toISOString();
  writeFileSync(STATE, `${JSON.stringify({ stackStart } satisfies E2eState, null, 2)}\n`);
}
