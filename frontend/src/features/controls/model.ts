import type { components } from '@/api/generated/schema';
import {
  DETECTORS,
  MAX_RECOMPUTE_MS,
  MAX_SERVICE_DATES,
  SOURCE_URI_SCHEMES,
  type Detector,
  type JobName,
} from '@/features/controls/jobs';
import { controlsCopy } from '@/i18n/controls';
import { fromLocalInput, zonedDate } from '@/lib/time';

// Rules of Controls (DOC-36 screens/ops-console-controls): which flags make which rows, what the listener lines say,
// and the client-side checks of "Run a job", which repeat those of E-33 (the API still decides).

type Schemas = components['schemas'];
export type RuntimeFlag = Schemas['RuntimeFlagResponse'];
export type JobSummary = Schemas['JobSummaryResponse'];
export type FeedVersion = Schemas['FeedVersionResponse'];
export type JobRequest = Schemas['JobRequestResponse'];

const copy = controlsCopy;
const HOUR_MS = 3_600_000;

// ---------------------------------------------------------------------------------------------------------------------
// Flags (§4)

export interface Consumer {
  key: string;
  id: 'gtfsRt' | 'ticketing';
  /** The sources its listeners read, in E-31 and E-60. */
  sources: readonly string[];
}

/** The two pause flags, one row each in the Pipeline card (DOC-15 §3). */
export const CONSUMERS: readonly Consumer[] = [
  {
    key: 'etl.consumer.gtfs-rt.paused',
    id: 'gtfsRt',
    sources: ['GTFS_RT_VEHICLE_POSITION', 'GTFS_RT_TRIP_UPDATE'],
  },
  { key: 'etl.consumer.ticketing.paused', id: 'ticketing', sources: ['TICKETING_SALES', 'TICKETING_SALE_POINTS'] },
];

/** The AI feature flags of the AI features card, in the order of §8. */
export const FEATURES = [
  'triage.dlq.enabled',
  'triage.auto-replay.enabled',
  'triage.ticketing.enabled',
  'triage.disruption.enabled',
  'triage.dispatch.enabled',
] as const;

export type FeatureKey = (typeof FEATURES)[number];

/** A flag as E-55 lists it; `undefined` when the API does not have it (the row is left out, never guessed). */
export function flagOf(flags: readonly RuntimeFlag[], key: string): RuntimeFlag | undefined {
  return flags.find((flag) => flag.key === key);
}

/** A pause flag or feature that has been in the same state for more than an hour gets a warning chip (§4, AC-3). */
export function longFor(flag: Pick<RuntimeFlag, 'updatedAt'>, now: number): number | undefined {
  const ms = now - Date.parse(flag.updatedAt);
  return ms > HOUR_MS ? ms : undefined;
}

// ---------------------------------------------------------------------------------------------------------------------
// Listener lines (§4)

/** The start of the newest E-31 bucket in which the source processed a micro-batch, if any in the 15 minutes. */
export function lastProcessed(summary: JobSummary | undefined, source: string): string | undefined {
  const series = summary?.stream.find((item) => item.source === source);
  let newest: string | undefined;
  for (const point of series?.points ?? []) {
    if (point.batches > 0 && (newest === undefined || Date.parse(point.bucketStart) > Date.parse(newest))) {
      newest = point.bucketStart;
    }
  }
  return newest;
}

// ---------------------------------------------------------------------------------------------------------------------
// Run a job (§6.1)

export interface JobForm {
  job: JobName;
  sourceUri: string;
  allowReactivate: boolean;
  /** `datetime-local` value in the agency's zone; empty = the hour that just ended. */
  hour: string;
  force: boolean;
  /** "YYYY-MM-DD", in the agency's zone; `null` until edited = yesterday on the business clock, read when checked. */
  serviceDates: string[] | null;
  detectors: Detector[];
  /** `datetime-local` values in the agency's zone. */
  from: string;
  to: string;
}

/** The form fields that can carry an error; named as E-33's parameters, so that `errors[].field` maps onto them. */
export type JobField = 'sourceUri' | 'hour' | 'serviceDates' | 'detectors' | 'fromTs' | 'toTs';
export type FieldErrors = Partial<Record<JobField, string>>;

export interface JobCheck {
  errors: FieldErrors;
  /** The body's `parameters` when the form is complete and valid. */
  parameters?: Record<string, unknown>;
}

export interface JobClock {
  /** `businessNow` in epoch ms. */
  now: number;
  timeZone: string;
}

export function emptyForm(job: JobName): JobForm {
  return {
    job,
    sourceUri: '',
    allowReactivate: false,
    hour: '',
    force: false,
    serviceDates: null,
    detectors: [...DETECTORS],
    from: '',
    to: '',
  };
}

/** The agency's yesterday on the business clock: the usual day to recompute. */
export function yesterday(clock: JobClock): string {
  return zonedDate(clock.now - 86_400_000, clock.timeZone);
}

/** The service dates the form holds; until edited, yesterday, so a page opened before E-60 answers is not wrong. */
export function serviceDatesOf(form: Pick<JobForm, 'serviceDates'>, clock: JobClock): string[] {
  return form.serviceDates ?? [yesterday(clock)];
}

/** An https, s3 or file URI (E-33); the rest of the check is etl-batch's allow-list (DOC-21 §1.1). */
export function validSourceUri(value: string): boolean {
  const match = /^([a-z][a-z0-9+.-]*):(.+)$/i.exec(value.trim());
  return match !== null && (SOURCE_URI_SCHEMES as readonly string[]).includes((match[1] ?? '').toLowerCase());
}

/**
 * The errors to show under each field, and the parameters to send once there are none. Empty required fields make
 * the form incomplete without an error; a value that breaks a rule shows the rule's sentence (§8).
 */
export function checkJob(form: JobForm, clock: JobClock): JobCheck {
  const errors: FieldErrors = {};
  const e = copy.job.error;
  switch (form.job) {
    case 'GtfsStaticLoadJob': {
      const uri = form.sourceUri.trim();
      if (uri !== '' && !validSourceUri(uri)) errors.sourceUri = e.uri;
      if (errors.sourceUri) return { errors };
      return {
        errors,
        parameters: { ...(uri === '' ? {} : { sourceUri: uri }), allowReactivate: form.allowReactivate },
      };
    }
    case 'EtaAggregationJob': {
      if (form.hour === '') return { errors, parameters: { force: form.force } };
      const hour = fromLocalInput(form.hour, clock.timeZone);
      if (Number.isNaN(hour)) return { errors };
      if (new Date(hour).getUTCMinutes() !== 0) errors.hour = e.hourStart;
      else if (hour > clock.now) errors.hour = e.future;
      if (errors.hour) return { errors };
      return { errors, parameters: { hour: new Date(hour).toISOString(), force: form.force } };
    }
    case 'OtpScorecardJob': {
      const dates = [...new Set(serviceDatesOf(form, clock))].sort();
      const today = zonedDate(clock.now, clock.timeZone);
      if (dates.length === 0 || dates.length > MAX_SERVICE_DATES) errors.serviceDates = e.dateCount;
      else if (dates.some((date) => date >= today)) errors.serviceDates = e.beforeToday;
      if (errors.serviceDates) return { errors };
      return { errors, parameters: { serviceDates: dates } };
    }
    case 'AnalyticsRecomputeJob': {
      if (form.detectors.length === 0) errors.detectors = e.detectors;
      const from = form.from === '' ? Number.NaN : fromLocalInput(form.from, clock.timeZone);
      const to = form.to === '' ? Number.NaN : fromLocalInput(form.to, clock.timeZone);
      if (!Number.isNaN(to) && to > clock.now) errors.toTs = e.future;
      else if (!Number.isNaN(from) && !Number.isNaN(to)) {
        if (from >= to) errors.toTs = e.order;
        else if (to - from > MAX_RECOMPUTE_MS) errors.toTs = e.maxRange;
      }
      if (Number.isNaN(from) || Number.isNaN(to) || Object.keys(errors).length > 0) return { errors };
      const detectors = DETECTORS.filter((detector) => form.detectors.includes(detector));
      return {
        errors,
        parameters: { detectors, fromTs: new Date(from).toISOString(), toTs: new Date(to).toISOString() },
      };
    }
    case 'PartitionMaintenanceJob':
      return { errors, parameters: {} };
  }
}

/** `errors[].field` of a 400 from E-33 ("parameters.serviceDates") as the form field it belongs to. */
export function fieldOfApiError(field: string): JobField | undefined {
  const name = field.replace(/^parameters\./, '');
  const known: readonly JobField[] = ['sourceUri', 'hour', 'serviceDates', 'detectors', 'fromTs', 'toTs'];
  return (known as readonly string[]).includes(name) ? (name as JobField) : undefined;
}

/** The parameter rows of the confirm dialog: name and the value as it will be sent. */
export function parameterRows(parameters: Record<string, unknown>): { name: string; value: string }[] {
  return Object.entries(parameters).map(([name, value]) => ({
    name,
    value: Array.isArray(value) ? value.join(', ') : String(value),
  }));
}

// ---------------------------------------------------------------------------------------------------------------------
// Requests (§5)

/** E-34 is polled every 2 s while the batch service has not picked the request up. */
export const PICKUP_POLL_MS = 2_000;
/**
 * Then, more slowly, until the job ends: the row's pill follows the run, and a GtfsStaticLoadJob that ends `DONE`
 * refreshes the feeds (§5). E-34 turns `DONE` or `FAILED` when the job ends (etl-batch JobRequests).
 */
export const RUNNING_POLL_MS = 10_000;

export function isTerminal(status: string | undefined): boolean {
  return status === 'DONE' || status === 'FAILED' || status === 'REJECTED';
}

/** Poll period of E-34 for a request in this status; `false` once it has ended. */
export function requestPollMs(status: string | undefined): number | false {
  if (status === undefined || status === 'PENDING') return PICKUP_POLL_MS;
  return isTerminal(status) ? false : RUNNING_POLL_MS;
}
