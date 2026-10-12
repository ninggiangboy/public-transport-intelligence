import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { TicketingPage } from '@/features/ticketing/components/TicketingPage';

// Ticketing anomalies (DOC-36 screens/ops-console-ticketing), read-only for viewers. The search params pass through
// untouched: the screen validates them (features/ticketing/search.ts), because a schema written here ships with the
// first paint (DR-105, DR-110).
export const Route = createFileRoute('/ops/ticketing')({
  validateSearch: (search: Record<string, unknown>) => search,
  component: Ticketing,
});

function Ticketing() {
  return (
    <RequireRole role="viewer">
      <TicketingPage raw={Route.useSearch()} />
    </RequireRole>
  );
}
