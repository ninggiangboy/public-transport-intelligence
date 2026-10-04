import { createFileRoute } from '@tanstack/react-router';

import { PlaceholderPage } from '@/app/shell/PlaceholderPage';
import { en } from '@/i18n/en';

// The shell routes this page already (P5-04); its screen replaces the placeholder later in phase 5.
export const Route = createFileRoute('/map')({
  staticData: { fullBleed: true },
  component: LiveMap,
});

function LiveMap() {
  return (
    <div className="px-4 py-5 md:px-7 md:py-6">
      <PlaceholderPage title={en.nav.items.map} />
    </div>
  );
}
