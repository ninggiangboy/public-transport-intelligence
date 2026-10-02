import { createRootRouteWithContext, Link, Outlet } from '@tanstack/react-router';

import { en } from '@/i18n/en';
import type { RouterContext } from '@/router';

export const Route = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
  notFoundComponent: NotFound,
});

function NotFound() {
  return (
    <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-4 p-6">
      <h1 className="text-2xl font-semibold">{en.notFound.title}</h1>
      <p className="text-muted-foreground">{en.notFound.body}</p>
      {/* "/" redirects to the map for anonymous users once the shell lands (DOC-34 §5.2). */}
      <Link to="/" className="underline underline-offset-4">
        {en.notFound.action}
      </Link>
    </main>
  );
}
