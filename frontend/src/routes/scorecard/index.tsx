import { createFileRoute } from '@tanstack/react-router';

import { PlaceholderPage } from '@/app/shell/PlaceholderPage';
import { en } from '@/i18n/en';

// The shell routes this page already (P5-04); its screen replaces the placeholder later in phase 5.
export const Route = createFileRoute('/scorecard/')({
  staticData: { hideStaleBanner: true },
  component: Scorecard,
});

function Scorecard() {
  return <PlaceholderPage title={en.nav.items.scorecard} group={en.nav.groups.analytics} role="viewer" />;
}
