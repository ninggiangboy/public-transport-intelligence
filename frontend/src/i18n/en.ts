import { designSystemCopy } from '@/i18n/design-system';
import { shellCopy } from '@/i18n/shell';
import { stopsCopy } from '@/i18n/stops';

// Every user-visible string of the app (DR-48, DOC-37). ESLint rejects JSX string literals anywhere else.
// This object ships with the first paint, so it holds the strings of the shell, the shared components and the stop
// page. The copy of other screens lives next to it in src/i18n/ (alerts.ts, overview.ts, catalog.ts) and is imported
// by those screens, so it loads with their chunk (DOC-34 §7, DR-105).
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
  // Find a stop and stop detail (P5-07); see stops.ts.
  ...stopsCopy,
} as const;
