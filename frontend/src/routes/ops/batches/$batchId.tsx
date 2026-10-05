import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { BatchLineagePage } from '@/features/ops-jobs/components/BatchLineagePage';

// The lineage of one batch id (DOC-36 screens/ops-console-jobs §6.2, FR-12.5).
export const Route = createFileRoute('/ops/batches/$batchId')({
  component: BatchLineage,
});

function BatchLineage() {
  const { batchId } = Route.useParams();
  return (
    <RequireRole role="viewer">
      <BatchLineagePage key={batchId} batchId={batchId} />
    </RequireRole>
  );
}
