// Copy of Controls (DOC-36 screens/ops-console-controls §8). Imported by the screen only, so it loads with its chunk.

const plural = (n: number, one: string, other: string) => `${n} ${n === 1 ? one : other}`;

export const controlsCopy = {
  title: 'Controls',
  crumb: 'Controls',
  subtitle: 'Changes apply within 5 seconds and record who made them.',
  readOnly: 'Read-only',
  readOnlyHelp: 'You can view everything here. Actions require the operator role.',
  requiresOperator: 'Requires the operator role.',
  toc: 'On this page',

  sections: {
    pipeline: 'Pipeline',
    ai: 'AI features',
    feeds: 'Schedule feed',
    runJob: 'Run a job',
  },
  intro: {
    pipeline: 'Pause consumers during maintenance. Messages keep landing in Kafka and are processed on resume.',
    ai: 'Every AI decision is logged with its confidence. Turning a feature off stops new decisions; nothing already decided changes.',
    feeds: 'The static GTFS timetable every prediction and scorecard is measured against.',
  },

  consumer: {
    gtfsRt: {
      name: 'GTFS-realtime consumer',
      description: 'Vehicle positions and trip updates from the Metro Transit feed.',
      group: 'GTFS-realtime',
    },
    ticketing: {
      name: 'Ticketing consumer',
      description: 'Ticket sales and sale-point updates.',
      group: 'ticketing',
    },
    running: 'Running',
    paused: 'Paused',
    pausedBy: (name: string, relative: string) => `Paused by ${name} ${relative}.`,
    staleSoon: 'The live map will show vehicles as stale after about 2 minutes.',
    pausedFor: (duration: string) => `Paused for ${duration}`,
  },
  listener: {
    lastProcessed: (source: string, relative: string) => `${source} · last processed ${relative}`,
    noBatches: (source: string) => `${source} · No batches in the last 15 min`,
    latestEvent: (relative: string) => `latest event ${relative}`,
    sourceQuiet: '(source quiet)',
  },

  feature: {
    'triage.dlq.enabled': {
      label: 'Classify new dead letters',
      description: 'New dead letters get a category, severity and routing decision.',
      offBody: 'New dead letters will wait in New until you turn it back on.',
    },
    'triage.auto-replay.enabled': {
      label: 'Automatic replay of dead letters',
      description: 'Confident transient failures are replayed without a person.',
      offBody: 'Dead letters will need a manual replay or confirmation.',
    },
    'triage.ticketing.enabled': {
      label: 'Classify ticketing anomalies',
      description: 'Ticketing anomalies get a category and severity.',
      offBody: 'New ticketing anomalies will show as Unclassified.',
    },
    'triage.disruption.enabled': {
      label: 'Enrich service disruptions',
      description: 'Disruptions get a likely cause and a data-issue check.',
      offBody: "Disruptions won't get a likely cause or data-issue check, so all stay visible to riders.",
    },
    'triage.dispatch.enabled': {
      label: 'Suggest dispatch actions',
      description: 'Bunching episodes get a suggested dispatch action.',
      offBody: "Bunching episodes won't get a suggested action.",
    },
  },
  flag: {
    changedBy: (name: string, relative: string) => `Changed by ${name} · ${relative}`,
    offFor: (duration: string) => `Off for ${duration}`,
  },

  confirm: {
    pauseTitle: (group: string) => `Pause the ${group} consumer?`,
    pauseBody:
      'New messages stay in Kafka and are processed when you resume. Live data will go stale after about 2 minutes.',
    pause: 'Pause',
    turnOffTitle: (feature: string) => `Turn off ${feature}?`,
    turnOff: 'Turn off',
    runTitle: (job: string) => `Run ${job}?`,
    parameters: 'Parameters',
    noParameters: 'No parameters.',
  },
  feedback: {
    paused: 'Consumer paused. Takes effect within 5 seconds.',
    resumed: 'Consumer resumed',
    turnedOff: (feature: string) => `${feature} turned off`,
    turnedOn: (feature: string) => `${feature} turned on`,
    requestSent: 'Request sent',
    viewRun: 'View run',
    rejected: (job: string, message: string) => `${job} request was rejected: ${message}`,
  },

  feed: {
    valid: (from: string, to: string) => `Valid ${from} – ${to}`,
    loaded: 'loaded',
    loadedCapital: 'Loaded',
    byRun: (id: string) => `by run #${id}`,
    checks: (errors: number, warnings: number) =>
      `${plural(errors, 'error', 'errors')} · ${plural(warnings, 'warning', 'warnings')}`,
    checksCell: (errors: number, warnings: number) =>
      `${plural(errors, 'error', 'errors')}, ${plural(warnings, 'warning', 'warnings')}`,
    loadNext: 'Load the next feed',
    urlLabel: 'GTFS zip URL',
    urlHelp: 'Paste the URL of a GTFS zip (https, s3 or file). It is validated before it can be activated.',
    load: 'Load feed',
    history: 'History',
    historyCaption: (n: number) => `${plural(n, 'feed version', 'feed versions')}, newest first`,
    noActive: 'No active feed',
    columns: {
      id: 'ID',
      status: 'Status',
      version: 'Version',
      publisher: 'Publisher',
      valid: 'Valid',
      loaded: 'Loaded',
      activated: 'Activated',
      checks: 'Checks',
      run: 'Run',
    },
    empty: 'No GTFS feeds loaded yet',
    emptyBody: 'Run GtfsStaticLoadJob to load the schedule.',
    panel: 'the schedule feeds',
  },

  job: {
    label: 'Job',
    run: 'Run job',
    recent: 'Recent requests',
    runNumber: (id: string) => `Run #${id}`,
    description: {
      GtfsStaticLoadJob: 'Download, validate and activate a GTFS schedule.',
      EtaAggregationJob: 'Rebuild typical delays for one hour.',
      OtpScorecardJob: 'Recompute on-time performance for the selected days.',
      AnalyticsRecomputeJob: 'Re-run bunching, disruption and ticketing detection for a period.',
      PartitionMaintenanceJob: 'Create upcoming partitions and drop expired ones.',
    },
    field: {
      sourceUri: 'Source URI',
      sourceUriHint: 'Leave empty to use the configured source.',
      sourceUriPlaceholder: 'https://',
      allowReactivate: 'Allow reactivating an older feed',
      hour: 'Hour',
      hourHint: 'Leave empty for the hour that just ended.',
      force: 'Recompute even if already done',
      serviceDates: 'Service dates',
      addDate: 'Add date',
      removeDate: (date: string) => `Remove ${date}`,
      detectors: 'Detectors',
      from: 'From',
      to: 'To',
      businessTime: 'Times are business time (simulated clock).',
    },
    detector: { BUNCHING: 'Bunching', DISRUPTION: 'Disruption', TICKETING: 'Ticketing' },
    error: {
      uri: 'Use an https, s3 or file URI.',
      hourStart: 'Pick the start of an hour.',
      future: "Can't be in the future.",
      beforeToday: 'Dates must be before today.',
      dateCount: 'Pick 1 to 31 dates.',
      order: 'End must be after start.',
      maxRange: 'Max range is 7 days.',
      detectors: 'Pick at least one detector.',
    },
  },
} as const;
