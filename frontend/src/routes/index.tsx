import { createFileRoute } from '@tanstack/react-router';

import { en } from '@/i18n/en';

// Placeholder until the shell (P5-04) redirects "/" to /overview or /map (DOC-34 §5.2).
export const Route = createFileRoute('/')({
  component: Home,
});

function Home() {
  return (
    <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-3 p-6">
      <h1 className="text-3xl font-semibold tracking-tight">{en.app.name}</h1>
      <p className="text-muted-foreground">{en.app.agency}</p>
      <p>{en.scaffold.body}</p>
    </main>
  );
}
