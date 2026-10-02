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
import { z } from 'zod';

const text = z.string();
const int = z.number();

/** The part of the envelope every event frame has (DOC-33 §2.2). `audience` and `source_record_ts` never reach SSE. */
const envelope = {
  id: text,
  channel: text.optional(),
  occurredAt: text.optional(),
  routeId: text.optional(),
};

function event<T extends string, D extends z.ZodType>(type: T, data: D) {
  return z.object({ ...envelope, type: z.literal(type), data });
}

// §5.1
export const vehiclePositionSchema = z.object({
  vehicleId: text,
  tripId: text,
  directionId: int,
  lat: z.number(),
  lon: z.number(),
  bearing: z.number().optional(),
  speedMps: z.number().optional(),
  currentStatus: text,
  stopId: text,
  currentStopSequence: int,
  occupancyStatus: text.optional(),
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
    directionId: int.optional(),
    vehicleLeader: text,
    vehicleFollower: text,
    gapSeconds: int,
    headwaySeconds: int,
    stopId: text.optional(),
    episodeStart: text.optional(),
  }),
);
const bunchingClosed = event(
  'bunching.closed',
  z.object({
    id: text,
    routeId: text.optional(),
    episodeEnd: text.optional(),
    closeReason: text.optional(),
    minGapSeconds: int.optional(),
  }),
);

// §5.3
const disruptionOpened = event(
  'disruption.opened',
  z.object({
    id: text,
    routeId: text.optional(),
    directionId: int.optional(),
    episodeStart: text.optional(),
    currentAvgDelaySeconds: z.number().optional(),
    baselineMeanSeconds: z.number().optional(),
    zScore: z.number().optional(),
    affectedStopIds: z.array(text).optional(),
  }),
);
const disruptionClosed = event(
  'disruption.closed',
  z.object({
    id: text,
    routeId: text.optional(),
    directionId: int.optional(),
    episodeEnd: text.optional(),
    closeReason: text.optional(),
    peakZScore: z.number().optional(),
    affectedStopIds: z.array(text).optional(),
  }),
);

// §5.4
const dispatchSuggested = event(
  'dispatch.suggested',
  z.object({
    id: text,
    bunchingId: text.optional(),
    routeId: text.optional(),
    action: text.optional(),
    actionConfidence: z.number().optional(),
    modelVersion: text.optional(),
    createdAt: text.optional(),
  }),
);

// §5.5: the alert record of `GET /alerts`, plus `resolvedAt`.
export const alertRecordSchema = z.object({
  id: text,
  type: text,
  severity: int,
  audience: text,
  routeId: text.optional(),
  refTable: text.optional(),
  refId: text.optional(),
  title: text,
  body: z.record(text, z.unknown()).default({}),
  createdAt: text,
  resolvedAt: text.optional(),
  acknowledgedBy: text.optional(),
  acknowledgedAt: text.optional(),
  link: text.optional(),
});
export type AlertRecord = z.infer<typeof alertRecordSchema>;

const alertCreated = event('alert.created', alertRecordSchema);
const alertUpdated = event('alert.updated', alertRecordSchema);
// §5.6
const alertRetracted = event('alert.retracted', z.object({ id: text, routeId: text.optional() }));

// §5.7
export const jobRunSchema = z.object({
  runId: text,
  kind: text,
  name: text,
  status: text,
  startedAt: text.optional(),
  endedAt: text.optional(),
  exitCode: text.optional(),
  exitMessage: text.optional(),
  readCount: int.default(0),
  writeCount: int.default(0),
  skipCount: int.default(0),
  jobExecutionId: int.optional(),
});
export type JobRunData = z.infer<typeof jobRunSchema>;

const jobRun = event('job.run', jobRunSchema);

// §5.8: three shapes told apart by `kind`.
const dlqChanged = event(
  'dlq.changed',
  z.discriminatedUnion('kind', [
    z.object({
      kind: z.literal('CREATED'),
      source: text.optional(),
      count: int.optional(),
      lastCreatedAt: text.optional(),
    }),
    z.object({
      kind: z.literal('UPDATED'),
      id: text,
      source: text.optional(),
      status: text,
      previousStatus: text.optional(),
      action: text.optional(),
      actor: text.optional(),
    }),
    z.object({
      kind: z.literal('BULK_UPDATED'),
      source: text.optional(),
      status: text.optional(),
      count: int.optional(),
    }),
  ]),
);

// §5.9, §5.10: no `id`, so that they never move `Last-Event-ID`.
const resync = z.object({
  type: z.literal('resync'),
  occurredAt: text.optional(),
  data: z.object({ reason: text, channels: z.array(text) }),
});
const heartbeat = z.object({
  type: z.literal('heartbeat'),
  data: z.object({ serverTime: text.optional(), businessNow: text }),
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
