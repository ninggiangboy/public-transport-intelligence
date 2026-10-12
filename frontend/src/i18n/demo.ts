// Copy of Demo control (DOC-36 screens/demo-control §8). Loaded with the screen's chunk, not with the first paint
// (DR-105). Parameter labels, scenario titles and descriptions come from the simulator's catalog (§6.1).

export const demoCopy = {
  title: 'Demo',
  crumb: 'Demo',
  heading: 'Demo scenarios',
  subtitle: 'Inject realistic problems into the simulated network and watch the platform react.',
  notEnabled: 'Demo control is not enabled on this server.',
  notEnabledBody: 'Start the stack with the demo profile to use this page.',

  simulator: {
    label: 'Simulator',
    running: 'Running',
    paused: 'Paused',
    businessTime: 'Business time',
    serviceDates: 'Service dates',
    serviceDate: (realDate: string, feedDate: string) => `${realDate} → feed ${feedDate}`,
    noFeedDate: 'no service',
    vehicles: 'Vehicles',
    gtfsRt: 'GTFS-rt',
    msgPerSecond: 'msg/s',
    ticketing: 'Ticketing',
    salesPerSecond: 'sales/s',
    panel: 'the simulator status',
  },

  speed: {
    label: 'Speed',
    paused: 'Paused',
    multiplier: (value: string) => `${value}×`,
    custom: 'Custom',
    customRate: 'Custom rate',
    more: 'Custom rate…',
    apply: 'Apply',
    gtfsRt: 'GTFS-rt',
    ticketing: 'Ticketing',
    range: 'Must be 0 or between 0.1 and 20.',
    loadRamp: 'Rate is controlled by the running load ramp.',
    updated: 'Rate updated',
    loadRampRunning: 'Rate is controlled by the running load ramp. Stop it first.',
    pauseTitle: (stream: string) => `Stop sending ${stream} data?`,
    pauseBody: 'Live data will go stale in about 2 minutes. Set the rate back to resume.',
    pauseConfirm: 'Stop sending',
    streams: { gtfsRt: 'GTFS-rt', ticketing: 'ticketing', both: 'GTFS-rt and ticketing' },
  },

  running: {
    label: 'Running scenarios',
    started: (time: string, requester: string) => `Running · started ${time} by ${requester}`,
    /** Under the time in the ring: "{mm:ss}" / "left". */
    leftWord: 'left',
    progressLabel: (percent: number) => `${percent}% of the planned duration`,
    whatHappened: 'What happened',
    afterStart: (duration: string) => `${duration} after start`,
    suggestion: (percent: string) => `Suggested action ready (${percent})`,
    deadLetters: (n: string) => `+${n} dead letters`,
    waiting: 'Waiting for the platform to react…',
    stop: 'Stop',
    stopAll: 'Stop all',
    none: 'No scenarios running',
  },

  library: {
    heading: 'Scenarios',
    note: 'Each scenario stops by itself when its duration ends.',
    start: 'Start scenario',
    running: 'Running',
  },

  dialog: {
    title: (title: string) => `Start ${title}?`,
    stopsAfter: (duration: string) => `Stops by itself after ${duration}.`,
    totalDuration: (duration: string) => `Total duration ${duration}`,
    reset: 'Reset',
    start: 'Start scenario',
    any: 'Any',
    pickRoute: 'Pick a route',
    listPlaceholder: '1, 2, 5, 10',
    units: { sec: 'sec', min: 'min', h: 'h' },
    unitLabel: (label: string) => `${label} unit`,
    yes: 'Yes',
    no: 'No',
  },

  /** Client-side checks of the form built from the catalog (§6.1). */
  rules: {
    number: 'Enter a number.',
    integer: 'Enter a whole number.',
    atLeast: (min: string) => `Must be at least ${min}.`,
    atMost: (max: string) => `Must be at most ${max}.`,
    pickOne: 'Pick at least one.',
    numbers: 'Enter numbers separated by commas.',
    option: 'Pick one of the options.',
  },

  history: {
    heading: 'History',
    caption: (n: number) => `Scenario runs, ${n} shown`,
    columns: {
      scenario: 'Scenario',
      status: 'Status',
      started: 'Started',
      ended: 'Ended',
      requestedBy: 'Requested by',
      parameters: 'Parameters',
    },
    empty: 'No scenario runs yet',
  },

  drawer: {
    title: (id: string) => `Scenario run ${id}`,
    parameters: 'Parameters',
    progress: 'Progress',
    requestedBy: (requester: string) => `Requested by ${requester}`,
    started: 'Started',
    plannedEnd: 'Planned end',
    ended: 'Ended',
    runAgain: 'Run again',
    gone: 'This run no longer exists.',
    panel: 'this scenario run',
  },

  confirm: {
    stopAllTitle: (title: string) => `Stop all ${title} runs?`,
    stopAllBody: (n: number) => `${n} ${n === 1 ? 'run' : 'runs'} will stop now.`,
    stopAllConfirm: 'Stop all',
  },

  feedback: {
    started: (title: string) => `${title} started`,
    stopped: (title: string) => `${title} stopped`,
    follow: {
      map: 'Watch on map',
      deadLetters: 'Open dead letters',
      ticketing: 'Open ticketing',
      pipeline: 'Open pipeline',
    },
  },

  errors: {
    conflict: (title: string) => `${title} is already running for this target.`,
    noEligibleVehicles: 'No suitable buses on this route right now. Try another route or direction.',
    unknownScenario: "This scenario isn't available.",
  },

  /** `requestedBy` (§8): `user:<name>` → the name, `experiment:<EXP>/<run>` → "Experiment {EXP}", `cli`. */
  requester: {
    experiment: (id: string) => `Experiment ${id}`,
    cli: 'Command line',
  },
} as const;
