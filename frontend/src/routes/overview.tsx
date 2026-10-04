import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { OverviewPage } from '@/features/overview/components/OverviewPage';
import { overviewSearch } from '@/features/overview/search';

// The viewer's start page (DOC-36 screens/overview): `period` applies to the on-time blocks only.
export const Route = createFileRoute('/overview')({
  validateSearch: overviewSearch,
  component: Overview,
});

function Overview() {
  const { period } = Route.useSearch();
  return (
    <RequireRole role="viewer">
      <OverviewPage period={period ?? '1d'} />
    </RequireRole>
  );
}
