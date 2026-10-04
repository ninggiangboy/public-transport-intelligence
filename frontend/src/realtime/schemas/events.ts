/**
 * Zod schemas of the SSE frames (DOC-33 §2.2, §5).
 *
 * HAND-WRITTEN until the backend publishes the JSON Schemas of DOC-33 §6
 * (`backend/common/src/main/resources/schemas/ui-events/<type>.schema.json`); then these files are generated with
 * `json-schema-to-zod` (DOC-44 §9.1) and this header goes away. The fields come from DOC-33 §5, cross-checked with the
 * publishers' records (analytics `BunchingMessages`, `DisruptionAlerts`, api `DeadLetterEvents`, `VehicleThrottle`).
 *
 * The schemas are tolerant on purpose (DOC-31 §12): unknown fields are dropped without an error, only what the cache
 * handlers need is required, and `null` is treated as absent (the parser removes nulls before validating).
 */
// zod/mini: the functional API, so that the bundle carries only the validators used here (DOC-34 §7).
import { z } from 'zod/mini';

const text = z.string();
const int = z.number();

/** The part of the envelope every event frame has (DOC-33 §2.2). `audience` and `source_record_ts` never reach SSE. */
const envelope = {
  id: text,
  channel: z.optional(text),
  occurredAt: z.optional(text),
  routeId: z.optional(text),
};

function event<T extends string, D extends z.ZodMiniType>(type: T, data: D) {
  return z.object({ ...envelope, type: z.literal(type), data });
}

// §5.1
export const vehiclePositionSchema = z.object({
  vehicleId: text,
  tripId: text,
  directionId: int,
  lat: z.number(),
  lon: z.number(),
  bearing: z.optional(z.number()),
  speedMps: z.optional(z.number()),
  currentStatus: text,
  stopId: text,
  currentStopSequence: int,
  occupancyStatus: z.optional(text),
  eventTimestamp: text,
});
export type VehiclePosition = z.infer<typeof vehiclePositionSchema>;

const vehiclesBatch = event('vehicles.batch', z.object({ routeId: text, vehicles: z.array(vehiclePositionSchema) }));

// §5.2 (`bunching.closed` carries `id`, `routeId`, `episodeEnd`, `closeReason`, `minGapSeconds`)
const bunchingOpened = event(
  'bunching.opened',
  z.object({
    id: text,
    routeId: text,
    directionId: z.optional(int),
    vehicleLeader: text,
    vehicleFollower: text,
    gapSeconds: int,
    headwaySeconds: int,
    stopId: z.optional(text),
    episodeStart: z.optional(text),
  }),
);
const bunchingClosed = event(
  'bunching.closed',
  z.object({
    id: text,
    routeId: z.optional(text),
    episodeEnd: z.optional(text),
    closeReason: z.optional(text),
    minGapSeconds: z.optional(int),
  }),
);

// §5.3
const disruptionOpened = event(
  'disruption.opened',
  z.object({
    id: text,
    routeId: z.optional(text),
    directionId: z.optional(int),
    episodeStart: z.optional(text),
    currentAvgDelaySeconds: z.optional(z.number()),
    baselineMeanSeconds: z.optional(z.number()),
    zScore: z.optional(z.number()),
    affectedStopIds: z.optional(z.array(text)),
  }),
);
const disruptionClosed = event(
  'disruption.closed',
  z.object({
    id: text,
    routeId: z.optional(text),
    directionId: z.optional(int),
    episodeEnd: z.optional(text),
    closeReason: z.optional(text),
    peakZScore: z.optional(z.number()),
    affectedStopIds: z.optional(z.array(text)),
  }),
);

// §5.4
const dispatchSuggested = event(
  'dispatch.suggested',
  z.object({
    id: text,
    bunchingId: z.optional(text),
    routeId: z.optional(text),
    action: z.optional(text),
    actionConfidence: z.optional(z.number()),
    modelVersion: z.optional(text),
    createdAt: z.optional(text),
  }),
);

// §5.5: the alert record of `GET /alerts`, plus `resolvedAt`.
export const alertRecordSchema = z.object({
  id: text,
  type: text,
  severity: int,
  audience: text,
  routeId: z.optional(text),
  refTable: z.optional(text),
  refId: z.optional(text),
  title: text,
  body: z._default(z.record(text, z.unknown()), {}),
  createdAt: text,
  resolvedAt: z.optional(text),
  acknowledgedBy: z.optional(text),
  acknowledgedAt: z.optional(text),
  link: z.optional(text),
});
export type AlertRecord = z.infer<typeof alertRecordSchema>;

const alertCreated = event('alert.created', alertRecordSchema);
const alertUpdated = event('alert.updated', alertRecordSchema);
// §5.6
const alertRetracted = event('alert.retracted', z.object({ id: text, routeId: z.optional(text) }));

// §5.7
export const jobRunSchema = z.object({
  runId: text,
  kind: text,
  name: text,
  status: text,
  startedAt: z.optional(text),
  endedAt: z.optional(text),
  exitCode: z.optional(text),
  exitMessage: z.optional(text),
  readCount: z._default(int, 0),
  writeCount: z._default(int, 0),
  skipCount: z._default(int, 0),
  jobExecutionId: z.optional(int),
});
export type JobRunData = z.infer<typeof jobRunSchema>;

const jobRun = event('job.run', jobRunSchema);

// §5.8: three shapes told apart by `kind`.
const dlqChanged = event(
  'dlq.changed',
  z.discriminatedUnion('kind', [
    z.object({
      kind: z.literal('CREATED'),
      source: z.optional(text),
      count: z.optional(int),
      lastCreatedAt: z.optional(text),
    }),
    z.object({
      kind: z.literal('UPDATED'),
      id: text,
      source: z.optional(text),
      status: text,
      previousStatus: z.optional(text),
      action: z.optional(text),
      actor: z.optional(text),
    }),
    z.object({
      kind: z.literal('BULK_UPDATED'),
      source: z.optional(text),
      status: z.optional(text),
      count: z.optional(int),
    }),
  ]),
);

// §5.9, §5.10: no `id`, so that they never move `Last-Event-ID`.
const resync = z.object({
  type: z.literal('resync'),
  occurredAt: z.optional(text),
  data: z.object({ reason: text, channels: z.array(text) }),
});
const heartbeat = z.object({
  type: z.literal('heartbeat'),
  data: z.object({ serverTime: z.optional(text), businessNow: text }),
});

/** Every event type this client understands, by its `type`. */
export const eventSchemas = {
  'vehicles.batch': vehiclesBatch,
  'bunching.opened': bunchingOpened,
  'bunching.closed': bunchingClosed,
  'disruption.opened': disruptionOpened,
  'disruption.closed': disruptionClosed,
  'dispatch.suggested': dispatchSuggested,
  'alert.created': alertCreated,
  'alert.updated': alertUpdated,
  'alert.retracted': alertRetracted,
  'job.run': jobRun,
  'dlq.changed': dlqChanged,
  resync,
  heartbeat,
} as const;

export type EventSchemas = typeof eventSchemas;
export type RealtimeEvent = { [K in keyof EventSchemas]: z.infer<EventSchemas[K]> }[keyof EventSchemas];
export type EventOf<T extends RealtimeEvent['type']> = Extract<RealtimeEvent, { type: T }>;
