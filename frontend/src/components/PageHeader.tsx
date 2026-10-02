import { ChevronRight } from 'lucide-react';
import { Fragment, type ReactNode } from 'react';

import { AppLink } from '@/components/AppLink';
import { en } from '@/i18n/en';

interface PageHeaderProps {
  crumbs?: { label: string; href?: string }[];
  /** The page's one `h1`. */
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
}

/** Breadcrumb, h1 and a one-line subtitle on the left, page actions on the right (DOC-35 §5.9). */
export function PageHeader({ crumbs, title, subtitle, actions }: PageHeaderProps) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
      <div className="min-w-0">
        {crumbs && crumbs.length > 0 ? (
          <nav aria-label={en.breadcrumb} className="mb-1.5">
            <ol className="flex flex-wrap items-center gap-1 text-label font-medium text-muted-foreground">
              {crumbs.map((crumb, index) => (
                <Fragment key={`${index}-${crumb.label}`}>
                  {index > 0 ? <ChevronRight className="size-3.5" aria-hidden="true" /> : null}
                  <li>
                    {crumb.href ? (
                      <AppLink href={crumb.href} className="hover:text-foreground hover:underline">
                        {crumb.label}
                      </AppLink>
                    ) : (
                      crumb.label
                    )}
                  </li>
                </Fragment>
              ))}
            </ol>
          </nav>
        ) : null}
        <h1 className="text-page font-semibold tracking-title text-foreground">{title}</h1>
        {subtitle ? <p className="mt-1 text-base text-muted-foreground">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  );
}
