import { Link, useRouter } from '@tanstack/react-router';
import type { ComponentProps } from 'react';

/**
 * A link inside the SPA: the router's `Link` when a router is mounted (client-side navigation), else a plain anchor,
 * so shared components also render in isolation, for example in unit tests and the catalogue.
 */
export function AppLink({ href, ...props }: Omit<ComponentProps<'a'>, 'href'> & { href: string }) {
  const router = useRouter({ warn: false }) as ReturnType<typeof useRouter> | undefined;
  if (!router) return <a href={href} {...props} />;
  // `href` is a path the caller built from the route table (DOC-34 §5.2); the router's `to` type only knows literals.
  return <Link to={href as '/'} {...props} />;
}
