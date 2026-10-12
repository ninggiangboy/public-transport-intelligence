import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { DemoPage } from '@/features/demo/components/DemoPage';

// Demo control (DOC-36 screens/demo-control): operators only. Without `demoControl` in env.js the page says the demo
// profile is off rather than calling the proxy (§1, AC-6). The search params pass through untouched: the screen
// validates them (features/demo/search.ts), so that the schema is not part of the first paint (DR-105, DR-110).
export const Route = createFileRoute('/ops/demo')({
  validateSearch: (search: Record<string, unknown>) => search,
  component: Demo,
});

function Demo() {
  return (
    <RequireRole role="operator">
      <DemoPage raw={Route.useSearch()} />
    </RequireRole>
  );
}
