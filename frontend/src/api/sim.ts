/**
 * The simulator's control API behind the proxy `/api/v1/sim/**` (DOC-32 E-90, DOC-25 §8), for Demo control (P5-13).
 *
 * HAND-WRITTEN: the proxy is not in the public openapi.json (DOC-32 §11), so its paths are typed here and every body is
 * checked with zod at run time. The fields come from DOC-25 §8, cross-checked with the simulator's records
 * (`SimStatus`, `ScenarioRun`, `ScenarioCatalog.Param`). The schemas are tolerant (DOC-31 §12): unknown fields are
 * dropped, and fields the simulator may leave out or send as `null` are `nullish`.
 */
import { z } from 'zod/mini';

import { createApiClient, write } from '@/api/client';
import { isApiError } from '@/api/problem';

const text = z.string();

/** `GET /sim/status`: rates, throughput and the business clock. `businessNow` is business time, the rest real time. */
export const simStatusSchema = z.object({
  clock: z.object({
    businessNow: text,
    /** ISO-8601 duration added to real time; negative ones read `PT-2H`. */
    offset: text,
    agencyTimeZone: text,
    serviceDates: z.array(z.object({ realDate: text, feedDate: z.nullish(text) })),
  }),
  rate: z.object({ gtfsRt: z.number(), ticketing: z.number() }),
  activeVehicles: z.number(),
  activeTrips: z.number(),
  /** Messages per second acknowledged by the broker, per topic, over the last 10 s. */
  messagesPerSecond: z.record(text, z.number()),
  ticketingPerSecond: z.number(),
  runningScenarios: z.array(z.object({ runId: text, scenario: text, plannedEndAt: z.nullish(text) })),
});

export const PARAM_TYPES = [
  'STRING',
  'INT',
  'DOUBLE',
  'BOOLEAN',
  'DURATION',
  'ENUM',
  'ENUM_LIST',
  'DOUBLE_LIST',
  'ROUTE',
] as const;
export type ParamType = (typeof PARAM_TYPES)[number];

/** A bound: a number, or an ISO-8601 string for a `DURATION`. */
const bound = z.union([z.number(), text]);

export const scenarioParamSchema = z.object({
  name: text,
  label: text,
  /** A type this UI does not know yet is shown as a text field. */
  type: z.catch(z.enum(PARAM_TYPES), 'STRING'),
  required: z.catch(z.boolean(), false),
  nullable: z.catch(z.boolean(), false),
  default: z.nullish(z.unknown()),
  min: z.nullish(bound),
  max: z.nullish(bound),
  options: z.nullish(z.array(text)),
  suggestions: z.nullish(z.array(text)),
});

export const scenarioSchema = z.object({
  name: text,
  title: text,
  description: text,
  /** `PER_TARGET`: runs on other routes or sale points may overlap; `SINGLE`: one run at a time. */
  concurrency: z.catch(z.enum(['PER_TARGET', 'SINGLE']), 'SINGLE'),
  params: z.array(scenarioParamSchema),
});

export const catalogSchema = z.object({ items: z.array(scenarioSchema) });

export const RUN_STATUSES = ['RUNNING', 'COMPLETED', 'STOPPED', 'FAILED'] as const;

/** One row of `sim.sim_scenario_run`, with its progress counters while it runs. Times are real time. */
export const scenarioRunSchema = z.object({
  runId: text,
  scenario: text,
  status: text,
  params: z.catch(z.record(text, z.unknown()), {}),
  /** `user:<name>`, `experiment:<EXP>/<run>` or `cli`. */
  requestedBy: text,
  startedAt: text,
  plannedEndAt: z.nullish(text),
  endedAt: z.nullish(text),
  progress: z.nullish(z.record(text, z.unknown())),
});

export const runsSchema = z.object({ items: z.array(scenarioRunSchema) });

export type SimStatus = z.infer<typeof simStatusSchema>;
export type ScenarioParam = z.infer<typeof scenarioParamSchema>;
export type Scenario = z.infer<typeof scenarioSchema>;
export type ScenarioRun = z.infer<typeof scenarioRunSchema>;

// The paths in the shape openapi-typescript writes, so that the client checks paths, parameters and bodies. Responses
// are `unknown` until the schemas above have read them.
interface Json<T> {
  content: Record<'application/json', T>;
}
interface NoContent {
  content?: never;
}
type Method = 'put' | 'post' | 'delete' | 'options' | 'head' | 'patch' | 'trace' | 'get';
type Item<Ops extends Partial<Record<Method, object>>> = Ops &
  Partial<Record<Exclude<Method, keyof Ops>, never>> & {
    parameters: { query?: never; header?: never; path?: never; cookie?: never };
  };
type Op<P extends object = { query?: never; path?: never }, B = never, R = unknown> = {
  parameters: P & { header?: never; cookie?: never };
  responses: R extends 204 ? { 204: NoContent } : { 200: Json<R>; 201: Json<R> };
} & ([B] extends [never] ? { requestBody?: never } : { requestBody: Json<B> });
interface Named {
  query?: never;
  path: { name: string };
}
interface ById {
  query?: never;
  path: { runId: string };
}

export interface SimPaths {
  '/api/v1/sim/status': Item<{ get: Op }>;
  '/api/v1/sim/rate': Item<{ put: Op<{ query?: never; path?: never }, { gtfsRt?: number; ticketing?: number }> }>;
  '/api/v1/sim/scenarios': Item<{ get: Op }>;
  '/api/v1/sim/scenarios/{name}': Item<{
    post: Op<Named, Record<string, unknown>>;
    delete: Op<Named, never, 204>;
  }>;
  '/api/v1/sim/scenario-runs': Item<{ get: Op<{ query?: { limit?: number; status?: string }; path?: never }> }>;
  '/api/v1/sim/scenario-runs/{runId}': Item<{ get: Op<ById>; delete: Op<ById, never, 204> }>;
}

const sim = createApiClient<SimPaths>();

export async function getSimStatus(): Promise<SimStatus> {
  return simStatusSchema.parse(await write(sim.GET('/api/v1/sim/status')));
}

/** `PUT /sim/rate` with one or both multipliers; the simulator answers with its status after the change. */
export async function putRate(rate: { gtfsRt?: number; ticketing?: number }): Promise<SimStatus> {
  return simStatusSchema.parse(await write(sim.PUT('/api/v1/sim/rate', { body: rate })));
}

export async function getCatalog(): Promise<Scenario[]> {
  return catalogSchema.parse(await write(sim.GET('/api/v1/sim/scenarios'))).items;
}

export async function getRuns(limit: number): Promise<ScenarioRun[]> {
  return runsSchema.parse(await write(sim.GET('/api/v1/sim/scenario-runs', { params: { query: { limit } } }))).items;
}

export async function getRun(runId: string): Promise<ScenarioRun> {
  return scenarioRunSchema.parse(
    await write(sim.GET('/api/v1/sim/scenario-runs/{runId}', { params: { path: { runId } } })),
  );
}

/** `POST /sim/scenarios/{name}`: 201 with the new run. */
export async function startScenario(name: string, params: Record<string, unknown>): Promise<ScenarioRun> {
  return scenarioRunSchema.parse(
    await write(sim.POST('/api/v1/sim/scenarios/{name}', { params: { path: { name } }, body: params })),
  );
}

/** Stops every running run of a scenario. */
export async function stopScenario(name: string): Promise<void> {
  await write(sim.DELETE('/api/v1/sim/scenarios/{name}', { params: { path: { name } } }));
}

/** Stops one run; idempotent. */
export async function stopRun(runId: string): Promise<void> {
  await write(sim.DELETE('/api/v1/sim/scenario-runs/{runId}', { params: { path: { runId } } }));
}

/**
 * The problem slug of a failed call, simulator slugs included (`scenario-conflict`, `load-ramp-running`...): the
 * proxy passes them on as they are, and they are not in the API's `ProblemSlug` (DOC-25 §8).
 */
export function simSlug(error: unknown): string | undefined {
  if (!isApiError(error)) return undefined;
  const slug: string | undefined = error.slug;
  return slug;
}
