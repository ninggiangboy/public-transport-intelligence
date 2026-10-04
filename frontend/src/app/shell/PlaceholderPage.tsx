import { Construction } from 'lucide-react';

import type { Role } from '@/app/access';
import { RequireRole } from '@/app/guards';
import { EmptyState } from '@/components/EmptyState';
import { PageHeader } from '@/components/PageHeader';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';

interface PlaceholderPageProps {
  title: string;
  /** Breadcrumb group, e.g. "Operations". */
  group?: string;
  role?: Role;
}

/** A routed page whose screen lands later in phase 5: the shell, guard and title work already. */
export function PlaceholderPage({ title, group, role }: PlaceholderPageProps) {
  useDocumentTitle(title);
  const body = <EmptyState icon={Construction} title={en.placeholder.title} description={en.placeholder.body} />;
  return (
    <>
      <PageHeader title={title} crumbs={group ? [{ label: group }, { label: title }] : undefined} />
      <div className="mt-6 rounded-lg border border-border bg-card shadow-xs">
        {role ? <RequireRole role={role}>{body}</RequireRole> : body}
      </div>
    </>
  );
}
