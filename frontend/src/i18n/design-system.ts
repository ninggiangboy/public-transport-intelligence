// Copy of the shared design-system components (DOC-35 §5) and the formats of DOC-37 §3 to §5. It lives in its own file
// so that the screens' sections of en.ts stay apart; en.ts spreads it, so `en.severity`, `en.error` ... are the keys.

/** Values a Problem Details response can fill into the sentences of `error.slug` (DOC-37 §2.3). */
export interface ErrorCopyParams {
  detail?: string;
  currentStatus?: string;
  field?: string;
  fields?: string;
  seconds?: number;
  thing?: string;
}

interface ErrorCopy {
  title: (p: ErrorCopyParams) => string;
  description?: (p: ErrorCopyParams) => string | undefined;
  /** Which button the state offers besides "Retry". */
  action?: 'retry' | 'reload' | 'signIn' | 'goBack' | 'viewReplay';
}

const detailOnly = (p: ErrorCopyParams) => p.detail;

export const designSystemCopy = {
  common: {
    retry: 'Retry',
    reload: 'Reload',
    cancel: 'Cancel',
    close: 'Close',
    save: 'Save',
    discardChanges: 'Discard changes',
    clearFilters: 'Clear filters',
    loadMore: 'Load more',
    show: 'Show',
    copy: 'Copy',
    copyId: 'Copy ID',
    copied: 'Copied',
    viewAsTable: 'View as table',
    viewAsChart: 'View as chart',
    goBack: 'Go back',
    signIn: 'Sign in',
    search: 'Search',
    apply: 'Apply',
    clear: 'Clear',
    any: 'Any',
    noMatches: 'No matches',
  },

  theme: {
    label: 'Theme',
    light: 'Light',
    dark: 'Dark',
    system: 'System',
  },

  time: {
    justNow: 'just now',
    now: 'now',
    ago: (n: number, unit: string) => `${n} ${unit} ago`,
    in: (n: number, unit: string) => `in ${n} ${unit}`,
    updated: (relative: string) => `Updated ${relative}`,
    asOf: (when: string) => `As of ${when}`,
    stale: 'Stale',
    lastUpdate: (relative: string) => `last update ${relative}`,
    zoneUtc: 'UTC',
  },

  format: {
    unit: { ms: 'ms', s: 's', min: 'min', h: 'h' },
    due: 'Due',
    zScore: (z: string) => `z = ${z}`,
    mph: (n: string) => `${n} mph`,
    minLate: (n: number) => `${n} min late`,
    minEarly: (n: number) => `${n} min early`,
  },

  delay: {
    class: {
      early: 'Early',
      'on-time': 'On time',
      late: 'Late',
      'very-late': 'Very late',
      unknown: 'No delay data',
    },
  },

  severity: {
    0: 'Informational',
    1: 'Needs attention',
    2: 'Urgent',
    unclassified: 'Unclassified',
  },

  /** Status labels per domain of StatusPill (DOC-37 §3.4, §3.5; scenarioRun is the simulator ledger of DOC-25). */
  status: {
    job: {
      STARTING: 'Starting',
      STARTED: 'Running',
      STOPPING: 'Stopping',
      STOPPED: 'Stopped',
      FAILED: 'Failed',
      COMPLETED: 'Completed',
      COMPLETED_WITH_SKIPS: 'Completed with skips',
      ABANDONED: 'Abandoned',
      UNKNOWN: 'Unknown',
    },
    // job_request, replay_request and sim_scenario_run share one tone table, so each domain labels every value of it.
    jobRequest: {
      PENDING: 'Queued',
      RUNNING: 'Running',
      DONE: 'Done',
      COMPLETED: 'Completed',
      FAILED: 'Failed',
      REJECTED: 'Rejected',
      STOPPED: 'Stopped',
    },
    replay: {
      PENDING: 'Queued',
      RUNNING: 'Running',
      DONE: 'Done',
      COMPLETED: 'Completed',
      FAILED: 'Failed',
      REJECTED: 'Rejected',
      STOPPED: 'Stopped',
    },
    dlq: {
      NEW: 'New',
      TRIAGING: 'Triaging',
      TRIAGED: 'Triaged',
      AUTO_REPLAY_SCHEDULED: 'Auto-replay scheduled',
      PENDING_CONFIRM: 'Awaiting confirmation',
      MANUAL: 'Needs manual review',
      REPLAY_REQUESTED: 'Replay requested',
      REPLAYED: 'Replayed',
      DISCARDED: 'Discarded',
      RESOLVED: 'Resolved',
    },
    feed: {
      STAGED: 'Staged',
      ACTIVE: 'Active',
      RETIRED: 'Retired',
      REJECTED: 'Rejected',
    },
    scenarioRun: {
      PENDING: 'Queued',
      RUNNING: 'Running',
      DONE: 'Done',
      COMPLETED: 'Completed',
      FAILED: 'Failed',
      REJECTED: 'Rejected',
      STOPPED: 'Stopped',
    },
  },

  confidence: {
    eta: {
      HIGH: 'High confidence',
      MEDIUM: 'Medium confidence',
      LOW: 'Low confidence',
      NONE: 'Schedule only',
    },
    ai: (percent: string) => `${percent} confidence`,
    aiLow: (percent: string) => `Low confidence (${percent})`,
    meterLabel: 'Confidence',
  },

  help: {
    etaConfidence: (sampleCount: number) =>
      `Predicted from ${sampleCount} past trips on this route at this stop. High: 30+ trips. Medium: 10–29. Low: fewer than 10.`,
    etaNone: 'No history for this stop at this hour yet, so we show the scheduled time.',
    aiConfidence: (percent: string, modelVersion?: string) =>
      `How sure the model is about this answer (${percent}). Below 60% we mark it as low confidence.${modelVersion ? ` Model: ${modelVersion}.` : ''}`,
    unclassified: "The AI hasn't classified this yet. It may be switched off or still working.",
    freshness: 'Newest data included here.',
    staleVehicle: (relative: string) => `Last position received ${relative}. The bus may have moved.`,
  },

  source: {
    GTFS_RT_VEHICLE_POSITION: 'Vehicle positions',
    GTFS_RT_TRIP_UPDATE: 'Trip updates',
    TICKETING_SALES: 'Ticket sales',
    TICKETING_SALE_POINTS: 'Sale points',
    GTFS_STATIC: 'GTFS schedule',
  },

  freshness: {
    noData: 'No data yet',
    sourceStaleTicketing: (age: string) => `No ticket sales received for ${age}. Anomaly detection may be behind.`,
    sourceStaleTicketingUnknown: 'No recent ticket sales received. Anomaly detection may be behind.',
    sourceStaleLive: (source: string, age: string) => `Live data is delayed. ${source} were last updated ${age} ago.`,
    sourceStaleLiveUnknown: (source: string) => `Live data is delayed. ${source} are not up to date.`,
  },

  route: {
    label: (displayName: string) => `Route ${displayName}`,
    select: {
      placeholder: 'Search routes',
      label: 'Routes',
      none: 'No routes selected',
      max: (max: number) => `Up to ${max} routes`,
      selected: (n: number) => `${n} selected`,
    },
  },

  filters: {
    all: 'All',
    selected: (n: number) => `${n} selected`,
    chip: (label: string, value: string) => `${label}: ${value}`,
    clearOne: (label: string) => `Clear ${label}`,
  },

  timeRange: {
    label: 'Time range',
    custom: 'Custom',
    from: 'From',
    to: 'To',
    apply: 'Apply',
    preset: (preset: string) => {
      const match = /^(\d+)([mhd])$/.exec(preset);
      if (!match) return preset;
      const n = Number(match[1]);
      const unit = match[2] === 'm' ? 'minute' : match[2] === 'h' ? 'hour' : 'day';
      return `Last ${n === 1 ? '' : `${n} `}${unit}${n === 1 ? '' : 's'}`;
    },
    tooLong: (limit: string) => `The range can be at most ${limit}.`,
    invalid: 'The start must be before the end.',
    latest: (date: string) => `The range can end on ${date} at the latest.`,
    required: 'Enter both times.',
  },

  newItems: {
    pill: (count: number, noun: { one: string; other: string }) =>
      `${count} new ${count === 1 ? noun.one : noun.other} · show`,
    alerts: { one: 'alert', other: 'alerts' },
  },

  states: {
    empty: {
      noResults: {
        title: 'No results match these filters',
        description: 'Try a wider time range or remove some filters.',
      },
      noData: { title: 'No data for this period' },
    },
    loading: 'Loading',
    noAccess: {
      signInTitle: 'Sign in to view this page',
      viewerBody: 'This page is for operations staff.',
      operatorBody: 'This page requires the operator role.',
      forbiddenTitle: "You don't have access to this page",
      forbiddenBody: 'This page requires the operator role. Ask an administrator for access.',
      goToOverview: 'Go to overview',
      unavailableTitle: "Sign-in isn't available",
      unavailableBody: 'This deployment only shows public pages.',
    },
  },

  error: {
    traceId: 'Trace ID',
    copyTrace: 'Copy trace ID',
    couldntLoad: (panel: string) => `Couldn't load ${panel}`,
    panelFallback: 'this panel',
    showingDataFrom: (title: string, relative: string) => `${title}. Showing data from ${relative}.`,
    viewReplay: 'View running replay',
    network: {
      title: "Can't reach the server",
      description: "Check your connection. We'll keep trying.",
    },
    generic: {
      title: 'Something went wrong',
      description: 'An unexpected error occurred. Quote the trace ID when reporting it.',
    },
    fieldError: (field: string, message: string) => `${field}: ${message}`,
    /** Per Problem slug of DOC-30 (DOC-37 §2.3). Slugs not listed here show the title and detail of the response. */
    slug: {
      'validation-error': {
        title: () => "This request isn't valid",
        description: detailOnly,
      },
      unauthorized: {
        title: () => 'Your session has expired',
        description: () => 'Sign in again to continue.',
        action: 'signIn',
      },
      forbidden: {
        title: () => "You don't have access",
        description: () => "Your account doesn't have the role needed for this.",
      },
      'not-found': {
        title: (p) => `${p.thing ?? 'Page'} not found`,
        description: () => 'It may have been removed, or the link is wrong.',
        action: 'goBack',
      },
      conflict: {
        title: () => 'This changed while you were working',
        description: () => 'Reload to see the latest state.',
        action: 'reload',
      },
      'dlq-invalid-state': {
        title: () => 'This dead letter has moved on',
        description: (p) =>
          p.currentStatus
            ? `Its status is now ${p.currentStatus}. The list has been refreshed.`
            : 'Its status has changed. The list has been refreshed.',
      },
      'replay-already-running': {
        title: () => 'A replay is already running',
        description: () => 'Only one replay per source can run at a time.',
        action: 'viewReplay',
      },
      'job-not-restartable': {
        title: () => "This run can't be restarted",
        description: detailOnly,
      },
      'job-not-running': {
        title: () => "This run isn't running",
        description: () => 'It may have finished while you were looking.',
      },
      'payload-too-large': {
        title: () => 'Payload is too large',
        description: () => 'Edited payloads must be 1 MiB or smaller.',
      },
      'invalid-payload': {
        title: () => "Payload doesn't match the schema",
        description: () => 'Fix the highlighted fields and save again.',
      },
      'pii-not-allowed': {
        title: () => "Personal data isn't allowed",
        description: (p) =>
          p.field ? `Remove the field ${p.field} and save again.` : 'Remove the personal data and save again.',
      },
      'business-key-changed': {
        title: () => "The record's identity can't change",
        description: (p) =>
          p.fields
            ? `Keep ${p.fields} as they were in the original payload.`
            : 'Keep the identifying fields as they were in the original payload.',
      },
      'replay-window-invalid': {
        title: () => 'Check the time range',
        description: detailOnly,
      },
      'unsupported-source': {
        title: () => "This source can't be replayed",
        description: () => 'Replay is available for vehicle positions, trip updates and ticket sales.',
      },
      'analytics-recompute-unavailable': {
        title: () => "Analytics recompute isn't available yet",
        description: () => 'Replay without recomputing analytics.',
      },
      'idempotency-key-reused': {
        title: () => 'This request was already sent with different values',
        description: () => 'Refresh the page and try again.',
        action: 'reload',
      },
      'job-not-allowed': {
        title: () => "This job can't be started from here",
        description: detailOnly,
      },
      'invalid-flag-value': {
        title: () => 'Invalid value for this flag',
        description: detailOnly,
      },
      'rate-limited': {
        title: () => 'Too many requests',
        description: (p) => (p.seconds === undefined ? 'Try again in a few seconds.' : `Try again in ${p.seconds} s.`),
      },
      'internal-error': {
        title: () => 'Something went wrong',
        description: () => 'An unexpected error occurred. Quote the trace ID when reporting it.',
        action: 'retry',
      },
      'simulator-unavailable': {
        title: () => "Simulator isn't responding",
        description: () => 'Check that the source-simulator container is running.',
        action: 'retry',
      },
      'service-unavailable': {
        title: () => 'Service is temporarily unavailable',
        description: () => "We'll retry in a few seconds.",
        action: 'retry',
      },
    } satisfies Record<string, ErrorCopy>,
  },

  confirm: {
    working: 'Working…',
    failed: 'The action failed.',
    reasonCount: (length: number, min: number, max: number) => `${length} / ${max} (at least ${min})`,
    reasonHint: (min: number, max: number) => `Enter ${min}–${max} characters.`,
  },

  kv: { empty: '—' },

  kpi: {
    delta: { up: 'up', down: 'down', flat: 'no change' },
    deltaLabel: (direction: string, value: string, caption?: string) =>
      `${direction} ${value}${caption ? ` ${caption}` : ''}`,
  },

  drawer: { close: 'Close panel' },

  json: {
    fileName: 'payload.json',
    original: 'Original',
    edited: 'Edited',
    copy: 'Copy',
    copied: 'Copied',
    label: (fileName: string) => `JSON: ${fileName}`,
    invalid: 'Not valid JSON, showing the text as is.',
  },

  sparkline: { empty: 'No data' },

  chart: {
    time: 'Time',
    /** The two ends of the heat legend, low to high (DOC-35 §7). */
    heatLow: { delay: 'Less', otp: 'More on time' },
    heatHigh: { delay: 'More late', otp: 'Less on time' },
    noData: 'No data',
  },

  lineStrip: {
    segment: (from: string, to: string, delay: string) => `${from} to ${to}: ${delay}`,
    state: { passed: 'Passed', current: 'Current stop', upcoming: 'Upcoming' },
  },

  breadcrumb: 'Breadcrumb',
} as const;
