import { createFileRoute } from '@tanstack/react-router';

import { RequireRole } from '@/app/guards';
import { PipelinePage } from '@/features/ops-jobs/components/PipelinePage';
import { pipelineSearch } from '@/features/ops-jobs/search';

// Pipeline (DOC-36 screens/ops-console-jobs): the stages of the ETL, its throughput and every run, for viewers.
export const Route = createFileRoute('/ops/jobs')({
  validateSearch: pipelineSearch,
  component: Pipeline,
});

function Pipeline() {
  return (
    <RequireRole role="viewer">
      <PipelinePage search={Route.useSearch()} />
    </RequireRole>
  );
}
