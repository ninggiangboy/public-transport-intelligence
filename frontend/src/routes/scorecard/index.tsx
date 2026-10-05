import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { ScorecardPage } from '@/features/scorecard/components/ScorecardPage';
import { scorecardSearch } from '@/features/scorecard/search';

// The route scorecard (DOC-36 screens/route-scorecard): daily aggregates, so no StaleBanner (DOC-37 §2.4).
export const Route = createFileRoute('/scorecard/')({
  validateSearch: scorecardSearch,
  staticData: { hideStaleBanner: true },
  component: Scorecard,
});

function Scorecard() {
  return (
    <RequireRole role="viewer">
      <ScorecardPage search={Route.useSearch()} />
    </RequireRole>
  );
}
