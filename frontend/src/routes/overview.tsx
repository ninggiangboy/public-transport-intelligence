import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod/mini';

import { RequireRole } from '@/app/guards';
import { OverviewPage } from '@/features/overview/components/OverviewPage';
import { PERIODS } from '@/features/overview/model';

// The viewer's start page (DOC-36 screens/overview): `period` applies to the on-time blocks only.
export const Route = createFileRoute('/overview')({
  validateSearch: z.object({ period: z.catch(z.optional(z.enum(PERIODS)), undefined) }),
  component: Overview,
});

function Overview() {
  const { period } = Route.useSearch();
  return (
    <RequireRole role="viewer">
      <OverviewPage period={period ?? '1d'} />
    </RequireRole>
  );
}
