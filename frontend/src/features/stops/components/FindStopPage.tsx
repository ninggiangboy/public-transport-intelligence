import { useNavigate } from '@tanstack/react-router';
import { useCallback } from 'react';

import { PageHeader } from '@/components/PageHeader';
import { StopFinder } from '@/features/stops/components/StopFinder';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';

/** /stops: find a stop by name or number (DOC-36 screens/stop-detail, "Tìm trạm"). */
export function FindStopPage({ q }: { q?: string }) {
  useDocumentTitle(en.stops.find.title);
  const navigate = useNavigate({ from: '/stops/' });
  const onQChange = useCallback(
    (next: string) => {
      // Typing replaces the history entry (DOC-34 §5.1).
      void navigate({ search: next ? { q: next } : {}, replace: true });
    },
    [navigate],
  );
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-5">
      <PageHeader title={en.stops.find.title} />
      <StopFinder q={q ?? ''} onQChange={onQChange} />
    </div>
  );
}
