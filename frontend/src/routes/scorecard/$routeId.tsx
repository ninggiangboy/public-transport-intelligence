import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { RouteDetailPage } from '@/features/scorecard/components/RouteDetailPage';
import { routeScorecardSearch } from '@/features/scorecard/search';

// One route of the scorecard (DOC-36 screens/route-scorecard §3): delays, stop profile and disruptions.
export const Route = createFileRoute('/scorecard/$routeId')({
  validateSearch: routeScorecardSearch,
  staticData: { hideStaleBanner: true },
  component: RouteScorecard,
});

function RouteScorecard() {
  const { routeId } = Route.useParams();
  return (
    <RequireRole role="viewer">
      <RouteDetailPage key={routeId} routeId={routeId} search={Route.useSearch()} />
    </RequireRole>
  );
}
