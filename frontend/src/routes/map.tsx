import { createFileRoute } from '@tanstack/react-router';

import { LiveMapPage } from '@/features/map/components/LiveMapPage';
import { mapSearch } from '@/features/map/search';

// The live map (DOC-36 screens/live-map): everyone sees vehicles, routes and public disruptions; viewers add the
// bunching overlay and dispatch suggestions. MapLibre comes with this route's chunk only (DOC-34 §7).
export const Route = createFileRoute('/map')({
  validateSearch: mapSearch,
  staticData: { fullBleed: true },
  component: LiveMap,
});

function LiveMap() {
  return <LiveMapPage search={Route.useSearch()} />;
}
