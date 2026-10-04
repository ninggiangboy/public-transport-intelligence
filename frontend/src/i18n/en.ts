import { designSystemCopy } from '@/i18n/design-system';
import { shellCopy } from '@/i18n/shell';

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
  notFound: {
    title: 'Page not found',
    body: "The page you're looking for doesn't exist.",
    action: 'Go to the map',
  },
  // Shared design-system components and formats (P5-03); see design-system.ts.
  ...designSystemCopy,
  // The app shell: navigation, account, search, banners (P5-04); see shell.ts.
  ...shellCopy,
} as const;
