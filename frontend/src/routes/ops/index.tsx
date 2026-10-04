import { createFileRoute, redirect } from '@tanstack/react-router';

// The ops console opens on Pipeline (DOC-34 §5.2).
export const Route = createFileRoute('/ops/')({
  beforeLoad: () => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- the router's redirect is thrown by design
    throw redirect({ to: '/ops/jobs', replace: true });
  },
});
