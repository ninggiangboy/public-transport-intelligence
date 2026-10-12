import { z } from 'zod/mini';

// Search params of /ops/replay (DOC-36 screens/ops-console-replay §2). Bad values are dropped, so an old link still
// opens. Apart from the screen's model, and parsed by the screen rather than the route, so that the route table, which
// ships with the first paint, does not carry it (DR-110).

/** A value that is dropped when it does not parse. */
const loose = <T extends z.ZodMiniType>(schema: T) => z.catch(z.optional(schema), undefined);
/** One value or a list of at most 20 (one value comes back from the URL as a plain string, src/lib/url.ts). */
const listOf = <T extends z.ZodMiniType>(item: T) =>
  loose(
    z.pipe(
      z.transform((value: unknown) => (typeof value === 'string' ? [value] : value)),
      z.array(item).check(z.maxLength(20)),
    ),
  );
const instant = z.string().check(z.refine((value) => !Number.isNaN(Date.parse(value))));

export const TABS = ['new', 'history'] as const;
export const KINDS = ['RAW_RANGE', 'DLQ_RECORD'] as const;
export const STATUSES = ['PENDING', 'RUNNING', 'DONE', 'FAILED'] as const;
/** The sources a raw zone replay accepts (DOC-22 §4.1); `GTFS_STATIC` is reloaded by GtfsStaticLoadJob instead. */
export const SOURCES = [
  'GTFS_RT_TRIP_UPDATE',
  'GTFS_RT_VEHICLE_POSITION',
  'TICKETING_SALES',
  'TICKETING_SALE_POINTS',
] as const;

export const replaySearch = z.object({
  tab: loose(z.enum(TABS)),
  /** Prefill of the form (tab `new`), read once when it opens. */
  source: loose(z.enum(SOURCES)),
  from: loose(instant),
  to: loose(instant),
  /** `1` or `0`; the URL parser may hand back a number. */
  recompute: loose(
    z.pipe(
      z.transform((value: unknown) => String(value)),
      z.enum(['1', '0']),
    ),
  ),
  kind: loose(z.enum(KINDS)),
  status: listOf(z.enum(STATUSES)),
  /** The replay open in the drawer. */
  replay: loose(z.string().check(z.minLength(1), z.maxLength(64))),
});

export type ReplaySearch = z.infer<typeof replaySearch>;
export type ReplayTab = (typeof TABS)[number];
export type ReplaySource = (typeof SOURCES)[number];
export type ReplayKind = (typeof KINDS)[number];
export type ReplayStatus = (typeof STATUSES)[number];
