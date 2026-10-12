import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { ControlsPage } from '@/features/controls/components/ControlsPage';

// Controls (DOC-36 screens/ops-console-controls): runtime flags, the schedule feed and "Run a job". Viewers see every
// block; the switches and forms act for operators only. The page shows no StaleBanner (DOC-37 §2.4).
export const Route = createFileRoute('/ops/controls')({
  staticData: { hideStaleBanner: true },
  component: Controls,
});

function Controls() {
  return (
    <RequireRole role="viewer">
      <ControlsPage />
    </RequireRole>
  );
}
