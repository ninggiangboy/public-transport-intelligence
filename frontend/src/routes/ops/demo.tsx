import { createFileRoute } from '@tanstack/react-router';

import { RequireDemo } from '@/app/guards';
import { NotFoundPage } from '@/app/shell/NotFoundPage';
import { PlaceholderPage } from '@/app/shell/PlaceholderPage';
import { en } from '@/i18n/en';

// Operators only, and only when env.js sets demoControl (DOC-34 §10); P5-13 builds the screen.
export const Route = createFileRoute('/ops/demo')({
  component: Demo,
});

function Demo() {
  return (
    <RequireDemo fallback={<NotFoundPage />}>
      <PlaceholderPage title={en.nav.items.demo} group={en.nav.groups.operations} role="operator" />
    </RequireDemo>
  );
}
