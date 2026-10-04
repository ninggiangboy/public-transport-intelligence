// Copy of the app shell (DOC-36 screens/shell-and-navigation §8, DOC-37 §2.4, §2.5, §2.8, §6). en.ts spreads it.

export const shellCopy = {
  nav: {
    primary: 'Primary',
    groups: { network: 'Network', analytics: 'Analytics', operations: 'Operations' },
    items: {
      overview: 'Overview',
      map: 'Live map',
      stops: 'Stops',
      alerts: 'Alerts',
      scorecard: 'Scorecard',
      pipeline: 'Pipeline',
      deadLetters: 'Dead letters',
      replay: 'Replay',
      ticketing: 'Ticketing',
      controls: 'Controls',
      demo: 'Demo',
    },
    mobile: { map: 'Map', stops: 'Stops', alerts: 'Alerts', more: 'More' },
    readOnly: 'Read-only',
    readOnlyHelp: 'You can view everything here. Actions require the operator role.',
    paused: 'Paused',
    pipelineFailed: 'A pipeline run failed in the last 24 hours',
    count: (n: number) => (n > 99 ? '99+' : String(n)),
    unacknowledgedAlerts: (n: number) => `${n} unacknowledged alerts`,
    openDeadLetters: (n: number) => `${n} open dead letters`,
    runningReplays: (n: number) => `${n} replays running`,
    ticketingAnomalies: (n: number) => `${n} ticketing anomalies in the last 24 hours`,
    collapse: 'Collapse sidebar',
    expand: 'Expand sidebar',
    openMenu: 'Open menu',
    menuTitle: 'Navigation',
    moreTitle: 'More',
  },

  brand: {
    name: 'Transit Intelligence',
    home: 'Transit Intelligence home',
  },

  skipToContent: 'Skip to content',

  liveFeed: {
    title: 'Live feed',
    rate: (rate: string) => `${rate} msg/s`,
    rateChart: 'Messages per minute, last 24 minutes',
  },

  realtime: {
    live: 'Live',
    reconnecting: 'Reconnecting…',
    polling: 'Polling',
    offline: 'Offline',
    updated: (relative: string) => `updated ${relative}`,
    every: (seconds: number) => `every ${seconds} s`,
    lastUpdate: (relative: string) => `last update ${relative}`,
    help: {
      live: 'Receiving live updates.',
      reconnecting: 'Trying to reconnect to live updates.',
      polling: (seconds: number) => `Live updates unavailable. Refreshing every ${seconds} s.`,
      offline: "You're offline.",
    },
  },

  banner: {
    offline: (relative: string) => `You're offline. Showing the last data received ${relative}.`,
    offlineNoData: "You're offline.",
    freshnessUnknown: "Can't check data freshness right now. Data may be out of date.",
    stale: (source: string, age: string) => `Live data is delayed. ${source} were last updated ${age} ago.`,
    staleNoData: 'No live vehicle data has been received yet.',
    polling: (seconds: number) => `Live updates are paused. Refreshing every ${seconds} s.`,
    reconnecting: 'Reconnecting to live updates…',
    opsNarrow: 'The ops console is designed for screens at least 1280 px wide.',
    dismiss: 'Dismiss',
  },

  account: {
    menu: 'Account menu',
    signIn: 'Sign in',
    signOut: 'Sign out',
    signedInAs: (name: string) => `Signed in as ${name}`,
    role: (role: string) => `Role: ${role}`,
    roles: { operator: 'Operator', viewer: 'Viewer', none: 'No role' },
    roleLine: { operator: 'Operator · on duty', viewer: 'Viewer', none: 'Signed in' },
    shortcuts: 'Keyboard shortcuts',
    theme: 'Theme',
    restoring: 'Restoring your session',
  },

  auth: {
    signingIn: 'Signing you in…',
    callbackFailedTitle: "Sign-in didn't complete",
    callbackFailedBody: 'Something went wrong while signing you in.',
    tryAgain: 'Try again',
    unreachable: "Couldn't reach the sign-in service. Try again.",
    expired: 'Your session has expired. Sign in again.',
  },

  search: {
    open: 'Open search',
    label: 'Search',
    shortcut: '⌘K',
    placeholder: 'Search pages, routes and stops',
    groups: { pages: 'Pages', routes: 'Routes', stops: 'Stops' },
    noMatches: (q: string) => `No matches for "${q}"`,
    stopsFailed: "Couldn't search stops",
  },

  shortcuts: {
    title: 'Keyboard shortcuts',
    description: 'Shortcuts other than ⌘K do nothing while you type in a field.',
    rows: [
      { keys: ['⌘K', 'Ctrl K'], label: 'Open search' },
      { keys: ['/'], label: 'Focus search or filters' },
      { keys: ['j', 'k'], label: 'Next / previous row' },
      { keys: ['Enter'], label: 'Open selected row' },
      { keys: ['Esc'], label: 'Close panel or dialog' },
    ],
  },

  footer: {
    attribution: 'Data: Metro Transit GTFS (public domain). Map: © OpenStreetMap contributors · Protomaps.',
    simulatedClock: (when: string) => `Simulated clock: ${when}`,
  },

  /** Pages whose screen lands in a later task of phase 5; the shell routes them already. */
  placeholder: {
    title: 'Coming soon',
    body: 'This screen is part of the dashboard and arrives in a later build.',
  },
} as const;
