import { createFileRoute, Navigate } from '@tanstack/react-router';

import { useAccess } from '@/app/access';
import { homePath } from '@/app/shell/nav-items';
import { PanelSkeleton } from '@/components/PanelSkeleton';

// "/" has no page: signed-in viewers and operators go to the overview, everyone else to the map (DOC-34 §5.2, UX-11).
export const Route = createFileRoute('/')({
  component: Home,
});

function Home() {
  const access = useAccess();
  if (access.pending) return <PanelSkeleton variant="detail" />;
  return <Navigate to={homePath(access) as '/'} replace />;
}
