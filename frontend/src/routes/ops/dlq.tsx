import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { DlqPage } from '@/features/dlq/components/DlqPage';

// Dead letters (DOC-36 screens/ops-console-dlq): viewers see every record, operators act on them. The search params
// pass through untouched: the screen validates them itself (features/dlq/search.ts), because a schema written here
// ships with the first paint, which has 0.5 KB left (DR-105, DR-110).
export const Route = createFileRoute('/ops/dlq')({
  validateSearch: (search: Record<string, unknown>) => search,
  component: DeadLetters,
});

function DeadLetters() {
  return (
    <RequireRole role="viewer">
      <DlqPage raw={Route.useSearch()} />
    </RequireRole>
  );
}
