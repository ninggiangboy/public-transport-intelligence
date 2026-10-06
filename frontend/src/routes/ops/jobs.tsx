import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { PipelinePage } from '@/features/ops-jobs/components/PipelinePage';

// Pipeline (DOC-36 screens/ops-console-jobs): the stages of the ETL, its throughput and every run, for viewers. The
// search params pass through untouched: the screen validates them (features/ops-jobs/search.ts), because a schema
// written here ships with the first paint (DR-105, DR-110).
export const Route = createFileRoute('/ops/jobs')({
  validateSearch: (search: Record<string, unknown>) => search,
  component: Pipeline,
});

function Pipeline() {
  return (
    <RequireRole role="viewer">
      <PipelinePage raw={Route.useSearch()} />
    </RequireRole>
  );
}
