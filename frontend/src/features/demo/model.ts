import { z } from 'zod/mini';

import type { Scenario, ScenarioParam, ScenarioRun, SimStatus } from '@/api/sim';
import { demoCopy } from '@/i18n/demo';
import { formatDuration } from '@/lib/format';
import { parseIsoDuration } from '@/lib/time';

// The model of Demo control (DOC-36 screens/demo-control): the start form built from the simulator's catalog (§6.1),
// the one-line summaries, the speed presets and where each scenario's effects show (§6). Pure functions, tested alone.

const rules = demoCopy.rules;
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

// ---------------------------------------------------------------------------------------------------------------------
// Durations

export const UNITS = ['sec', 'min', 'h'] as const;
export type DurationUnit = (typeof UNITS)[number];
const UNIT_MS: Record<DurationUnit, number> = { sec: SECOND, min: MINUTE, h: HOUR };

/** An ISO-8601 duration from milliseconds, in whole seconds: "PT20M", "PT1H30M", "PT45S"; "PT0S" for zero. */
export function toIsoDuration(ms: number): string {
  const total = Math.round(ms / SECOND);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const parts = `${hours ? `${hours}H` : ''}${minutes ? `${minutes}M` : ''}${seconds ? `${seconds}S` : ''}`;
  return `PT${parts || '0S'}`;
}

/** Milliseconds of a catalog duration: an ISO-8601 string, or seconds if a number comes instead. */
export function durationMs(value: unknown): number {
  if (typeof value === 'number') return value * SECOND;
  return typeof value === 'string' ? parseIsoDuration(value) : Number.NaN;
}

/** A duration as the form shows it: the largest unit that divides it ("PT20M" → 20 min, "PT90S" → 90 sec). */
export function splitDuration(ms: number): DurationValue {
  if (!Number.isFinite(ms) || ms <= 0) return { amount: '', unit: 'min' };
  const unit: DurationUnit = ms % HOUR === 0 ? 'h' : ms % MINUTE === 0 ? 'min' : 'sec';
  return { amount: String(ms / UNIT_MS[unit]), unit };
}

/** "20 min" for a catalog duration; the raw text when it cannot be read. */
export function formatCatalogDuration(value: unknown): string {
  const ms = durationMs(value);
  return Number.isNaN(ms) ? String(value) : formatDuration(ms);
}

/**
 * "+14 h 30 min", "−2 h" for the simulator's clock offset; `undefined` for none. Java writes a negative duration with a
 * sign on each part ("PT-2H-30M").
 */
export function formatOffset(iso: string): string | undefined {
  const negative = iso.includes('-');
  const ms = parseIsoDuration(iso.replaceAll('-', ''));
  if (Number.isNaN(ms) || ms === 0) return undefined;
  return `${negative ? '−' : '+'}${formatDuration(ms)}`;
}

// ---------------------------------------------------------------------------------------------------------------------
// The start form (§6.1)

export interface DurationValue {
  amount: string;
  unit: DurationUnit;
}

/** What a field holds while it is edited: text for numbers and lists, so that a half-typed value stays as typed. */
export type FieldValue = string | boolean | string[] | DurationValue;
export type FormValues = Record<string, FieldValue>;
export type FieldErrors = Partial<Record<string, string>>;

/** `ENUM` value of "Any" (`nullable`). */
export const ANY = '';

/** True when every option is a number: the value is sent as one (`directionId`: "0" → 0, DOC-25 §8). */
export function numericOptions(param: ScenarioParam): boolean {
  const options = param.options ?? [];
  return options.length > 0 && options.every((option) => option.trim() !== '' && !Number.isNaN(Number(option)));
}

function listText(value: unknown): string {
  return Array.isArray(value) ? value.map(String).join(', ') : '';
}

/** A scalar as text; anything else as JSON. */
function asText(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

/** The field's value for `value` (a default, or a parameter of an earlier run for "Run again"). */
function fieldValue(param: ScenarioParam, value: unknown): FieldValue {
  switch (param.type) {
    case 'BOOLEAN':
      return value === true;
    case 'DURATION':
      return splitDuration(value === undefined || value === null ? Number.NaN : durationMs(value));
    case 'ENUM_LIST':
      return Array.isArray(value) ? value.map(String) : [];
    case 'DOUBLE_LIST':
      return listText(value);
    default:
      return asText(value);
  }
}

/** The form filled with the catalog's defaults, or with `prefill` where it has a value ("Run again", §6). */
export function initialValues(scenario: Scenario, prefill?: Record<string, unknown>): FormValues {
  const values: FormValues = {};
  for (const param of scenario.params) {
    const fromRun = prefill && Object.hasOwn(prefill, param.name) ? prefill[param.name] : undefined;
    values[param.name] = fieldValue(param, fromRun ?? param.default);
  }
  return values;
}

/** Marks a required field left empty: it keeps "Start scenario" disabled without a message (DR-112 §2). */
const MISSING = '\u0000missing';
/** A value that is not sent: the simulator uses its default (§6.1). */
const OMIT = Symbol('omit');

function formatBound(param: ScenarioParam, bound: number | string): string {
  return param.type === 'DURATION' ? formatCatalogDuration(bound) : String(bound);
}

function boundsOf(param: ScenarioParam) {
  const read = (bound: number | string | null | undefined) =>
    bound === undefined || bound === null ? undefined : param.type === 'DURATION' ? durationMs(bound) : Number(bound);
  return { min: read(param.min), max: read(param.max) };
}

/** The message for a number outside the param's bounds, if it is. */
function rangeError(param: ScenarioParam, value: number): string | undefined {
  const { min, max } = boundsOf(param);
  if (min !== undefined && param.min !== undefined && param.min !== null && value < min) {
    return rules.atLeast(formatBound(param, param.min));
  }
  if (max !== undefined && param.max !== undefined && param.max !== null && value > max) {
    return rules.atMost(formatBound(param, param.max));
  }
  return undefined;
}

/** Reads one field into the value sent, or fails with the copy of §6.1 (or {@link MISSING}). */
function readField(param: ScenarioParam, raw: FieldValue): { value: unknown } | { error: string } {
  const empty = () => (param.required ? { error: MISSING } : { value: OMIT });
  switch (param.type) {
    case 'BOOLEAN':
      return { value: raw === true };
    case 'INT':
    case 'DOUBLE': {
      const text = typeof raw === 'string' ? raw.trim() : '';
      if (text === '') return empty();
      const number = Number(text);
      if (Number.isNaN(number)) return { error: rules.number };
      if (param.type === 'INT' && !Number.isInteger(number)) return { error: rules.integer };
      const error = rangeError(param, number);
      return error ? { error } : { value: number };
    }
    case 'DURATION': {
      const duration = typeof raw === 'object' && !Array.isArray(raw) ? raw : { amount: '', unit: 'min' as const };
      const text = duration.amount.trim();
      if (text === '') return empty();
      const amount = Number(text);
      if (Number.isNaN(amount) || amount < 0) return { error: rules.number };
      const ms = Math.round(amount * UNIT_MS[duration.unit]);
      const error = rangeError(param, ms);
      return error ? { error } : { value: toIsoDuration(ms) };
    }
    case 'ENUM': {
      const text = typeof raw === 'string' ? raw : ANY;
      if (text === ANY) return param.nullable ? { value: null } : empty();
      if (param.options && !param.options.includes(text)) return { error: rules.option };
      return { value: numericOptions(param) ? Number(text) : text };
    }
    case 'ENUM_LIST': {
      const list = Array.isArray(raw) ? raw : [];
      // An empty list would be refused, or read as "all" when left out: neither is what the form shows (§6.1).
      return list.length === 0 ? { error: rules.pickOne } : { value: list };
    }
    case 'DOUBLE_LIST': {
      const text = typeof raw === 'string' ? raw.trim() : '';
      if (text === '') return { error: rules.numbers };
      const numbers = text.split(',').map((part) => (part.trim() === '' ? Number.NaN : Number(part.trim())));
      if (numbers.some((number) => Number.isNaN(number))) return { error: rules.numbers };
      for (const number of numbers) {
        const error = rangeError(param, number);
        if (error) return { error };
      }
      return { value: numbers };
    }
    default: {
      const text = typeof raw === 'string' ? raw.trim() : '';
      return text === '' ? empty() : { value: text };
    }
  }
}

/**
 * The schema of a scenario's form, built at run time from its catalog entry (§6.1): `required`, `min`, `max`,
 * `nullable` and `options`. It reads the {@link FormValues} into the body of `POST /sim/scenarios/{name}`; its issues
 * carry the parameter as path and the copy of §6.1.
 */
export function scenarioForm(scenario: Scenario) {
  const shape: Record<string, z.ZodMiniType> = {};
  for (const param of scenario.params) {
    shape[param.name] = z.transform((raw: unknown, ctx) => {
      const read = readField(param, raw as FieldValue);
      if ('error' in read) {
        ctx.issues.push({ code: 'custom', message: read.error, input: raw });
        return z.NEVER;
      }
      return read.value;
    });
  }
  return z.pipe(
    z.object(shape),
    z.transform((values) => Object.fromEntries(Object.entries(values).filter(([, value]) => value !== OMIT))),
  );
}

export type CheckedForm =
  { ok: true; body: Record<string, unknown> } | { ok: false; errors: FieldErrors; missing: boolean };

/** The body to send, or the first message per field; `missing` when a required field is still empty. */
export function checkScenarioForm(scenario: Scenario, values: FormValues): CheckedForm {
  const result = scenarioForm(scenario).safeParse(values);
  if (result.success) return { ok: true, body: result.data };
  const errors: FieldErrors = {};
  let missing = false;
  for (const issue of result.error.issues) {
    const field = String(issue.path[0] ?? '');
    if (issue.message === MISSING) {
      missing = true;
      continue;
    }
    errors[field] ??= issue.message;
  }
  return { ok: false, errors, missing };
}

/**
 * How long a run of the scenario lasts with these values: its `duration`, or for `load-ramp` the steps (DOC-25 §7.8):
 * `stepDuration` × steps, twice less one with `rampDown`. `undefined` while the values do not tell.
 */
export function plannedDurationMs(scenario: Scenario, values: Record<string, unknown>): number | undefined {
  if (values.duration !== undefined) {
    const ms = durationMs(values.duration);
    return Number.isNaN(ms) ? undefined : ms;
  }
  if (scenario.name !== 'load-ramp') return undefined;
  const steps = Array.isArray(values.steps) ? values.steps.length : 0;
  const step = durationMs(values.stepDuration);
  if (steps === 0 || Number.isNaN(step)) return undefined;
  return step * (values.rampDown === true ? 2 * steps - 1 : steps);
}

/** {@link plannedDurationMs} from the form as it is: the duration fields that read, whatever the others hold. */
export function plannedFromForm(scenario: Scenario, values: FormValues): number | undefined {
  const picked: Record<string, unknown> = {};
  for (const param of scenario.params) {
    if (!['duration', 'steps', 'stepDuration', 'rampDown'].includes(param.name)) continue;
    const read = readField(param, values[param.name] ?? '');
    if ('value' in read && read.value !== OMIT) picked[param.name] = read.value;
  }
  return plannedDurationMs(scenario, picked);
}

/** The defaults of the catalog as a body, for the chips of a scenario card and the planned duration. */
export function defaultsOf(scenario: Scenario): Record<string, unknown> {
  return Object.fromEntries(
    scenario.params
      .filter((param) => param.default !== undefined && param.default !== null)
      .map((p) => [p.name, p.default]),
  );
}

// ---------------------------------------------------------------------------------------------------------------------
// Runs

/** `requestedBy` for people (§8). */
export function requesterName(requestedBy: string): string {
  if (requestedBy.startsWith('user:')) return requestedBy.slice('user:'.length);
  if (requestedBy.startsWith('experiment:')) {
    return demoCopy.requester.experiment(requestedBy.slice('experiment:'.length).split('/')[0] ?? '');
  }
  if (requestedBy === 'cli') return demoCopy.requester.cli;
  return requestedBy;
}

/** The parameters that name a run's target; a run card shows them in its title, not in its summary. */
export const TARGET_PARAMS = ['routeId', 'salePointId'] as const;

/** The route or sale point a run acts on: what "for this target" means (DOC-25 §7.1). */
export function targetOf(params: Record<string, unknown>): { routeId?: string; salePointId?: string } {
  const pick = (value: unknown) => (typeof value === 'string' && value !== '' ? value : undefined);
  const routeId = pick(params.routeId);
  const salePointId = pick(params.salePointId);
  return { ...(routeId ? { routeId } : {}), ...(salePointId ? { salePointId } : {}) };
}

/** A parameter value in a summary: durations in words, lists with commas, booleans as Yes/No. */
export function formatParamValue(param: ScenarioParam | undefined, value: unknown): string {
  if (value === null || value === undefined) return demoCopy.dialog.any;
  if (param?.type === 'DURATION' || (typeof value === 'string' && value.startsWith('PT'))) {
    return formatCatalogDuration(value);
  }
  if (typeof value === 'boolean') return value ? demoCopy.dialog.yes : demoCopy.dialog.no;
  if (Array.isArray(value)) return value.map(asText).join(', ');
  return asText(value);
}

/**
 * "Direction 0 · Vehicle pairs 1 · Duration 20 min": the parameters on one line, in catalog order, labelled by the
 * catalog. `omit` leaves out what the line shows elsewhere (the route of a run card).
 */
export function paramsSummary(
  scenario: Scenario | undefined,
  params: Record<string, unknown>,
  omit: readonly string[] = [],
): string {
  const ordered = scenario
    ? [
        ...scenario.params.map((param) => param.name).filter((name) => Object.hasOwn(params, name)),
        ...Object.keys(params).filter((name) => !scenario.params.some((param) => param.name === name)),
      ]
    : Object.keys(params);
  return ordered
    .filter((name) => !omit.includes(name))
    .map((name) => {
      const param = scenario?.params.find((item) => item.name === name);
      return `${param?.label ?? name} ${formatParamValue(param, params[name])}`;
    })
    .join(' · ');
}

/** Elapsed share of `startedAt`–`plannedEndAt` (0–1) and the time left, at `now` (real time). */
export function runProgress(run: Pick<ScenarioRun, 'startedAt' | 'plannedEndAt'>, now: number) {
  const start = Date.parse(run.startedAt);
  const end = run.plannedEndAt ? Date.parse(run.plannedEndAt) : Number.NaN;
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return undefined;
  const leftMs = Math.max(0, end - now);
  return { share: Math.min(1, Math.max(0, (now - start) / (end - start))), leftMs };
}

/** "12:40" for the time left; hours as "1:05:00". */
export function formatClock(ms: number): string {
  const total = Math.ceil(ms / SECOND);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = String(total % 60).padStart(2, '0');
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
}

/**
 * Whether the scenario already runs for the target the card would start (§4): any run for a `SINGLE` scenario; for
 * `PER_TARGET`, a run on the default route or sale point. A scenario without a default target is never "Running".
 */
export function runsForDefault(scenario: Scenario, running: readonly ScenarioRun[]): boolean {
  const mine = running.filter((run) => run.scenario === scenario.name);
  if (scenario.concurrency === 'SINGLE') return mine.length > 0;
  const target = scenario.params.find((param) => param.name === 'routeId' || param.name === 'salePointId');
  if (target?.default === undefined || target.default === null) return false;
  return mine.some((run) => run.params[target.name] === target.default);
}

// ---------------------------------------------------------------------------------------------------------------------
// Where the effects show (§6)

export type FollowKind = 'map' | 'deadLetters' | 'ticketing' | 'pipeline';

/** The page that shows the scenario's effects, with the button of the toast and the run card. */
export function followOf(
  name: string,
  params: Record<string, unknown>,
): { kind: FollowKind; href: string } | undefined {
  switch (name) {
    case 'bunching':
    case 'disruption': {
      const { routeId } = targetOf(params);
      return { kind: 'map', href: routeId ? `/map?route=${encodeURIComponent(routeId)}` : '/map' };
    }
    case 'bad-data':
    case 'late-delivery':
      return { kind: 'deadLetters', href: '/ops/dlq' };
    case 'ticket-spike':
    case 'refund-burst':
      return { kind: 'ticketing', href: '/ops/ticketing' };
    case 'load-ramp':
    case 'duplicates':
      return { kind: 'pipeline', href: '/ops/jobs' };
    default:
      return undefined;
  }
}

/** The alerts "What happened" watches for a run (§4.1): by route or sale point, of the scenario's type when known. */
export function watchedAlerts(
  run: Pick<ScenarioRun, 'scenario' | 'params'>,
): { routeId?: string; salePointId?: string; type?: string } | undefined {
  const target = targetOf(run.params);
  const type: Partial<Record<string, string>> = {
    bunching: 'BUNCHING',
    disruption: 'DISRUPTION',
    'ticket-spike': 'TICKETING_ANOMALY',
    'refund-burst': 'TICKETING_ANOMALY',
  };
  if (target.routeId) return { routeId: target.routeId, ...(type[run.scenario] ? { type: type[run.scenario] } : {}) };
  if (target.salePointId) return { salePointId: target.salePointId, type: 'TICKETING_ANOMALY' };
  return undefined;
}

/** Scenarios whose effect is dead letters: "+{n} dead letters" (§4.1). */
export function watchesDeadLetters(name: string): boolean {
  return name === 'bad-data' || name === 'late-delivery';
}

// ---------------------------------------------------------------------------------------------------------------------
// Speed (§4)

export const RATE_PRESETS = ['0', '0.5', '1', '2', '5', '10'] as const;
export type RateChoice = (typeof RATE_PRESETS)[number] | 'custom';

/** The preset both multipliers sit on, or `custom` when they differ or match none. */
export function rateChoice(rate: SimStatus['rate']): RateChoice {
  if (rate.gtfsRt !== rate.ticketing) return 'custom';
  return RATE_PRESETS.find((preset) => Number(preset) === rate.gtfsRt) ?? 'custom';
}

/** `0`, or 0.1–20 (DOC-25 §6.5). */
export function rateAllowed(value: number): boolean {
  return value === 0 || (value >= 0.1 && value <= 20);
}

/** The streams a change stops (a multiplier going to 0 from another value), for "Stop sending {stream} data?". */
export function pausedStreams(
  current: SimStatus['rate'],
  next: { gtfsRt?: number; ticketing?: number },
): 'gtfsRt' | 'ticketing' | 'both' | undefined {
  const gtfsRt = next.gtfsRt === 0 && current.gtfsRt !== 0;
  const ticketing = next.ticketing === 0 && current.ticketing !== 0;
  if (gtfsRt && ticketing) return 'both';
  if (gtfsRt) return 'gtfsRt';
  return ticketing ? 'ticketing' : undefined;
}

/** True while a `load-ramp` run owns the multipliers (DOC-25 §7.8): "Speed" is disabled. */
export function loadRampRunning(status: SimStatus | undefined): boolean {
  return status?.runningScenarios.some((run) => run.scenario === 'load-ramp') ?? false;
}

/** Messages per second over both GTFS-realtime topics. */
export function gtfsRtPerSecond(status: SimStatus): number {
  return Object.values(status.messagesPerSecond).reduce((sum, value) => sum + value, 0);
}

/** "{Label} {default}" chips of a scenario card: the parameters with a default, at most `limit`. */
export function defaultChips(scenario: Scenario, limit = 3): string[] {
  return scenario.params
    .filter((param) => param.default !== undefined && param.default !== null && param.type !== 'ENUM_LIST')
    .slice(0, limit)
    .map((param) => `${param.label} ${formatParamValue(param, param.default)}`);
}
