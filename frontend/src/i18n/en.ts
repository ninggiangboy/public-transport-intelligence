import { designSystemCopy } from '@/i18n/design-system';

// Every user-visible string of the app (DR-48, DOC-37). ESLint rejects JSX string literals anywhere else.
export const en = {
  app: {
    name: 'Public Transport Intelligence',
    shortName: 'PTI',
    agency: 'Metro Transit · Twin Cities',
  },
  page: {
    /** document.title pattern of DOC-37 §6. */
    title: (page: string) => `${page} — PTI`,
  },
  scaffold: {
    body: 'The dashboard screens arrive during Phase 5. This page confirms that the build, the router and the styles work.',
  },
  notFound: {
    title: 'Page not found',
    body: "The page you're looking for doesn't exist.",
    action: 'Go to the map',
  },
  // Shared design-system components and formats (P5-03); see design-system.ts.
  ...designSystemCopy,
} as const;
