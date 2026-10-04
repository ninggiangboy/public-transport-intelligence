import { createFileRoute } from '@tanstack/react-router';

import { FindStopPage } from '@/features/stops/components/FindStopPage';
import { findStopSearch } from '@/features/stops/search';

// Find a stop (DOC-36 screens/stop-detail): `q` searches E-06; empty shows saved and recent stops.
export const Route = createFileRoute('/stops/')({
  validateSearch: findStopSearch,
  component: FindStop,
});

function FindStop() {
  const { q } = Route.useSearch();
  return <FindStopPage q={q} />;
}
