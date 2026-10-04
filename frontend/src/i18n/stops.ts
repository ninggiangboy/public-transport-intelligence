// Copy of "Find a stop" and stop detail (DOC-36 screens/stop-detail §8, DOC-37 §3.2, §4.4, §5). en.ts spreads it.

const plural = (n: number, one: string, other: string) => `${n} ${n === 1 ? one : other}`;

export const stopsCopy = {
  stops: {
    find: {
      title: 'Find a stop',
      label: 'Search stops',
      placeholder: 'Stop name or number',
      saved: 'Saved stops',
      recent: 'Recent stops',
      clear: 'Clear',
      results: 'Results',
      noMatchTitle: (q: string) => `No stops match "${q}"`,
      noMatchBody: 'Try the stop number shown on the sign, or part of the street name.',
      failed: "Couldn't search stops",
    },
    header: {
      plate: 'STOP',
      code: (code: string) => `#${code}`,
      codeMobile: (code: string) => `Stop ${code}`,
      stepFree: 'Step-free',
      routes: (n: number) => plural(n, 'route', 'routes'),
      save: 'Save stop',
      saved: 'Saved',
      viewOnMap: 'View on map',
      back: 'Stops',
    },
    departures: {
      title: 'Departures',
      asOf: (when: string) => `Predictions as of ${when}`,
      asOfShort: (when: string) => `As of ${when}`,
      all: 'All',
      routeFilter: 'Filter by route',
      directionFilter: 'Direction',
      showLater: 'Show later departures',
      note: 'Times are predictions. Schedule-only trips have no prediction history yet.',
      live: 'Live',
      scheduleOnly: 'Schedule only',
      scheduled: 'Scheduled',
      scheduledAt: (time: string) => `Scheduled ${time}`,
      emptyTitle: 'No upcoming departures',
      emptyBody: 'No trips are scheduled at this stop in the next 90 min.',
      panel: 'departures',
      confidence: (level: string, n: number) => `${level} · ${plural(n, 'trip', 'trips')}`,
      /** The sentence a screen reader reads for one departure. */
      rowLabel: (p: {
        route: string;
        headsign?: string;
        eta: string;
        time: string;
        delay?: string;
        confidence: string;
      }) =>
        [`Route ${p.route}${p.headsign ? ` to ${p.headsign}` : ''}`, p.eta, p.time, p.delay, p.confidence]
          .filter(Boolean)
          .join(', '),
      etaSpoken: (minutes: number) => (minutes <= 1 ? 'due now' : `in ${minutes} minutes`),
      delaySpoken: (seconds: number) => {
        if (Math.abs(seconds) < 60) return 'on time';
        const minutes = Math.round(Math.abs(seconds) / 60);
        return `${plural(minutes, 'minute', 'minutes')} ${seconds > 0 ? 'late' : 'early'}`;
      },
      confidenceSpoken: (level: string, n: number) =>
        level === 'NONE' ? 'schedule only' : `${level.toLowerCase()} confidence based on ${plural(n, 'trip', 'trips')}`,
    },
    direction: {
      /** E-02 direction labels; any other label shows as it is. */
      labels: { NB: 'Northbound', SB: 'Southbound', EB: 'Eastbound', WB: 'Westbound' } as Partial<
        Record<string, string>
      >,
      fallback: (directionId: number) => `Direction ${directionId}`,
    },
    disruption: {
      body: (delay: string, time: string) => `Buses are running up to ${delay} late since ${time}.`,
      since: (time: string) => `Since ${time}.`,
      seeAlert: 'See alert',
      more: (n: number) => `and ${n} more`,
      backToNormal: (route: string) => `Service on Route ${route} is back to normal`,
    },
    reliability: {
      title: 'Reliability here',
      when: (weekday: string, hour: string) => `${weekday}s around ${hour}`,
      stats: (avg: string, p90: string) => `avg ${avg} · p90 ${p90}`,
      notEnough: 'Not enough history yet',
    },
    notFound: {
      title: 'Stop not found',
      body: 'Check the stop number, or search for the stop by name.',
    },
    help: {
      realtime: 'Based on the latest real-time update from the bus.',
    },
  },
} as const;
