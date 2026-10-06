// Sample data of the /_ui catalogue (DOC-35 §10). Names and sample values here are developer-facing, not product copy.
import { ApiError, type ProblemDetails, type ProblemSlug } from '@/api/problem';
import type { RouteOption } from '@/components/RouteSelect';
import { statusValues, type StatusDomain } from '@/components/status-map';
import { TONES } from '@/components/tone';
import { DELAY_CLASSES } from '@/lib/delay';

export { DELAY_CLASSES, TONES };

export const STATUS_DOMAINS: StatusDomain[] = ['job', 'jobRequest', 'replay', 'dlq', 'feed', 'scenarioRun'];

export function statusesOf(domain: StatusDomain): string[] {
  return [...statusValues(domain), 'SOMETHING_NEW'];
}

/** An instant `seconds` before now. */
export function ago(seconds: number): string {
  return new Date(Date.now() - seconds * 1000).toISOString();
}

export const ROUTES: RouteOption[] = [
  { routeId: '901', shortName: 'Blue', longName: 'Blue Line', routeType: 0, color: '0053A0', textColor: 'FFFFFF' },
  { routeId: '902', shortName: 'Green', longName: 'Green Line', routeType: 0, color: '00803D', textColor: 'FFFFFF' },
  {
    routeId: '6',
    shortName: '6',
    longName: 'Xerxes Av / Hennepin Av',
    routeType: 3,
    color: 'FFFF00',
    textColor: 'FFFFFF',
  },
  {
    routeId: '5',
    shortName: '5',
    longName: 'Chicago Av / Fremont Av',
    routeType: 3,
    color: 'FFD700',
    textColor: '000000',
  },
  { routeId: '21', shortName: '21', longName: 'Uptown / Lake St / Selby Av', routeType: 3 },
  { routeId: '18', shortName: '18', longName: 'Nicollet Av / Southtown', routeType: 3 },
  {
    routeId: '22',
    shortName: '22',
    longName: 'Chicago Av / Brooklyn Center',
    routeType: 3,
    color: '6A3FC6',
    textColor: 'FFFFFF',
  },
];

export const STOPS = [
  { id: 'a', name: 'Nicollet Mall', meta: 'Transfer to 21', eta: '4:02 PM', state: 'passed', major: true },
  { id: 'b', name: 'Lake St', eta: '4:11 PM', state: 'passed' },
  { id: 'c', name: 'Chicago Av', meta: 'Scheduled 4:31 PM', eta: '+3 min', state: 'current' },
  { id: 'd', name: '46th St', eta: '4:42 PM', state: 'upcoming' },
  { id: 'e', name: 'Southtown', eta: '4:55 PM', state: 'upcoming', major: true },
] as const;

export const SAMPLE_JSON = {
  routeId: '901',
  vehicle: { id: 'v-1042', position: { lat: 44.94812, lon: -93.278 } },
  delaySeconds: 213,
  occupancy: null,
  stale: false,
};

/** 10,000 fake dead letters for the virtualised table: ids, rules and messages are made up (DS-06). */
export const SAMPLE_ROWS = Array.from({ length: 10_000 }, (_, index) => ({
  id: `0192f5a1-7c1e-7d3a-9b2c-${String(index).padStart(12, '0')}`,
  rule: index % 4 === 0 ? 'DQ-03' : 'DQ-06',
  message: `position.latitude of record ${index + 1} is outside the service area`,
}));

export const SAMPLE_JSON_EDITED = {
  ...SAMPLE_JSON,
  delaySeconds: 240,
  stale: true,
};

function problem(slug: ProblemSlug, status: number, extra: Record<string, unknown> = {}): ApiError {
  const body = {
    type: `urn:pti:problem:${slug}`,
    title: `Title of ${slug}`,
    status,
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    detail: `Detail of ${slug}.`,
    ...extra,
  } as ProblemDetails;
  return new ApiError(status, body);
}

/** A 409 the dead-letter list can answer with; also the error of the failing ConfirmDialog. */
export const DLQ_CONFLICT = problem('dlq-invalid-state', 409, { currentStatus: 'RESOLVED' });

export const ERRORS: { key: string; error: unknown }[] = [
  { key: 'network', error: new TypeError('Failed to fetch') },
  {
    key: 'validation-error',
    error: problem('validation-error', 400, { errors: [{ field: 'from', message: 'must be before to' }] }),
  },
  { key: 'unauthorized', error: problem('unauthorized', 401) },
  { key: 'forbidden', error: problem('forbidden', 403) },
  { key: 'not-found', error: problem('not-found', 404) },
  { key: 'method-not-allowed', error: problem('method-not-allowed', 405) },
  { key: 'not-acceptable', error: problem('not-acceptable', 406) },
  { key: 'conflict', error: problem('conflict', 409) },
  { key: 'dlq-invalid-state', error: DLQ_CONFLICT },
  { key: 'replay-already-running', error: problem('replay-already-running', 409, { existingReplayId: 'rp-1' }) },
  { key: 'job-not-restartable', error: problem('job-not-restartable', 409) },
  { key: 'job-not-running', error: problem('job-not-running', 409) },
  { key: 'payload-too-large', error: problem('payload-too-large', 413) },
  { key: 'unsupported-media-type', error: problem('unsupported-media-type', 415) },
  { key: 'invalid-payload', error: problem('invalid-payload', 422) },
  { key: 'pii-not-allowed', error: problem('pii-not-allowed', 422, { field: 'rider_email' }) },
  { key: 'business-key-changed', error: problem('business-key-changed', 422, { fields: ['trip_id', 'stop_id'] }) },
  { key: 'replay-window-invalid', error: problem('replay-window-invalid', 422) },
  { key: 'unsupported-source', error: problem('unsupported-source', 422) },
  { key: 'analytics-recompute-unavailable', error: problem('analytics-recompute-unavailable', 422) },
  { key: 'idempotency-key-reused', error: problem('idempotency-key-reused', 422) },
  { key: 'job-not-allowed', error: problem('job-not-allowed', 422) },
  { key: 'invalid-flag-value', error: problem('invalid-flag-value', 422) },
  { key: 'rate-limited', error: problem('rate-limited', 429, { retryAfter: 12 }) },
  { key: 'internal-error', error: problem('internal-error', 500) },
  { key: 'simulator-unavailable', error: problem('simulator-unavailable', 502) },
  { key: 'service-unavailable', error: problem('service-unavailable', 503) },
];

export const PALETTE = {
  surfaces: [
    'background',
    'surface',
    'card',
    'popover',
    'muted',
    'muted-2',
    'border',
    'border-strong',
    'input',
    'ring',
  ],
  text: ['foreground', 'foreground-2', 'muted-foreground', 'subtle-foreground'],
  accent: [
    'primary',
    'primary-hover',
    'primary-foreground',
    'primary-soft',
    'primary-soft-fg',
    'destructive',
    'destructive-foreground',
  ],
  delay: [
    'delay-early',
    'delay-on-time',
    'delay-late',
    'delay-very-late',
    'delay-unknown',
    'bunching',
    'bunching-soft',
  ],
  chart: ['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5', 'chart-6', 'chart-7', 'chart-8'],
  heat: ['heat-1', 'heat-2', 'heat-3', 'heat-4', 'heat-5', 'heat-6', 'heat-7'],
  map: ['map-land', 'map-water', 'map-park', 'map-block', 'map-road', 'map-casing', 'map-label'],
} as const;

export const TYPE_SCALE = [
  { token: 'text-xs', className: 'text-xs' },
  { token: 'label', className: 'text-label font-medium' },
  { token: 'text-sm', className: 'text-sm' },
  { token: 'text-nav', className: 'text-nav font-medium' },
  { token: 'text-base', className: 'text-base' },
  { token: 'title-panel', className: 'text-panel font-semibold tracking-title' },
  { token: 'title-page', className: 'text-page font-semibold tracking-title' },
  { token: 'kpi', className: 'text-kpi font-semibold tracking-kpi tabular-nums' },
  { token: 'display', className: 'text-display font-semibold tracking-kpi tabular-nums' },
] as const;

/** Names of the specimens: identifiers of the components, shown as headings. */
export const NAMES = {
  fonts: 'Geist, Geist Mono',
  severityBadge: 'SeverityBadge',
  statusPill: 'StatusPill',
  confidenceChip: 'ConfidenceChip',
  confidenceMeter: 'ConfidenceMeter',
  delayBadge: 'DelayBadge',
  routeBadge: 'RouteBadge',
  timestamp: 'Timestamp, RelativeTime, Duration',
  freshness: 'FreshnessIndicator, SourceStaleNotice',
  filters: 'MultiSelectFilter, TimeRangePicker, RouteSelect',
  controls: 'SegmentedControl, NewItemsPill',
  states: 'EmptyState, NoAccessState, PanelSkeleton',
  errorState: 'ErrorState',
  actions: 'ConfirmDialog, DetailDrawer, toast',
  data: 'CopyButton, IdText, KeyValueList, KpiCard, Sparkline',
  table: 'DataTable',
  json: 'JsonViewer, JsonEditor',
  layout: 'PageHeader, Card, Callout, ActivityTimeline, SplitView',
  lineStrip: 'LineStrip',
} as const;
