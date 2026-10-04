import { Link } from '@tanstack/react-router';

import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';

/** Any URL the app does not know (DOC-37 §6), inside the shell so that navigation still works. */
export function NotFoundPage() {
  useDocumentTitle(en.notFound.title);
  return (
    <div className="mx-auto flex max-w-xl flex-1 flex-col justify-center gap-4 py-10">
      <h1 className="text-page font-semibold tracking-title">{en.notFound.title}</h1>
      <p className="text-muted-foreground">{en.notFound.body}</p>
      <Link to="/map" className="text-primary underline-offset-4 hover:underline">
        {en.notFound.action}
      </Link>
    </div>
  );
}
