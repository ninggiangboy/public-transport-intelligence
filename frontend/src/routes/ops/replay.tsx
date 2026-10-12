import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { ReplayPage } from '@/features/replay/components/ReplayPage';

// Replay from the raw zone (DOC-36 screens/ops-console-replay): viewers see the history, operators start replays. The
// search params pass through untouched: the screen validates them (features/replay/search.ts), because a schema
// written here ships with the first paint (DR-105, DR-110).
export const Route = createFileRoute('/ops/replay')({
  validateSearch: (search: Record<string, unknown>) => search,
  component: Replay,
});

function Replay() {
  return (
    <RequireRole role="viewer">
      <ReplayPage raw={Route.useSearch()} />
    </RequireRole>
  );
}
