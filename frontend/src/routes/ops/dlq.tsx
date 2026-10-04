import { createFileRoute } from '@tanstack/react-router';

import { PlaceholderPage } from '@/app/shell/PlaceholderPage';
import { en } from '@/i18n/en';

// The shell routes this page already (P5-04); its screen replaces the placeholder later in phase 5.
export const Route = createFileRoute('/ops/dlq')({
  component: DeadLetters,
});

function DeadLetters() {
  return <PlaceholderPage title={en.nav.items.deadLetters} group={en.nav.groups.operations} role="viewer" />;
}
