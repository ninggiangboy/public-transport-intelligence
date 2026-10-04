import { createFileRoute } from '@tanstack/react-router';

import { StopDetailPage } from '@/features/stops/components/StopDetailPage';
import { stopDetailSearch } from '@/features/stops/search';

// Stop detail (DOC-36 screens/stop-detail). It renders from E-07 and E-08 without waiting for /me or the event
// stream (DOC-34 §7).
export const Route = createFileRoute('/stops/$stopId')({
  validateSearch: stopDetailSearch,
  component: StopDetail,
});

function StopDetail() {
  const { stopId } = Route.useParams();
  const search = Route.useSearch();
  return <StopDetailPage key={stopId} stopId={stopId} search={search} />;
}
