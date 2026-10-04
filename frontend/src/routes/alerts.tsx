import { createFileRoute } from '@tanstack/react-router';

import { AlertsPage } from '@/features/alerts/components/AlertsPage';
import { alertsSearch } from '@/features/alerts/search';

// The alert feed (DOC-36 screens/alert-feed): public alerts for everyone, every audience for staff.
export const Route = createFileRoute('/alerts')({
  validateSearch: alertsSearch,
  component: Alerts,
});

function Alerts() {
  return <AlertsPage search={Route.useSearch()} />;
}
