import { z } from 'zod/mini';

// Search params of /ops/ticketing (DOC-36 screens/ops-console-ticketing §2). Bad values are dropped, so an old link
// still opens. The screen parses them, not the route, so that the schema stays out of the first paint (DR-110).

/** A value that is dropped when it does not parse. */
const loose = <T extends z.ZodMiniType>(schema: T) => z.catch(z.optional(schema), undefined);
/** One value or a list of at most 20 (one value comes back from the URL as a plain string or number). */
const listOf = <T extends z.ZodMiniType>(item: T) =>
  loose(
    z.pipe(
      z.transform((value: unknown) => (typeof value === 'string' || typeof value === 'number' ? [value] : value)),
      z.array(item).check(z.maxLength(20)),
    ),
  );
const instant = z.string().check(z.refine((value) => !Number.isNaN(Date.parse(value))));
const id = z.string().check(z.minLength(1), z.maxLength(64));

export const WINDOWS = ['6h', '24h', '7d'] as const;
export const TRIGGERS = ['VOLUME', 'REFUND_RATIO', 'BOTH'] as const;
/** The categories of DOC-37 §3 plus `unclassified`, which E-15 takes for "no category yet". */
export const CATEGORIES = ['fraud_suspect', 'system_error', 'promo_spike', 'normal', 'unclassified'] as const;

export const ticketingSearch = z.object({
  window: loose(z.enum(WINDOWS)),
  /** A fixed range on the event axis; when both are set, `window` is ignored (at most 31 days). */
  from: loose(instant),
  to: loose(instant),
  category: listOf(z.string().check(z.regex(/^[a-z_]{1,40}$/))),
  severity: listOf(z.coerce.number().check(z.int(), z.gte(0), z.lte(2))),
  trigger: loose(z.enum(TRIGGERS)),
  salePoint: loose(id),
  /** The anomaly open in the detail panel. */
  anomaly: loose(id),
});

export type TicketingSearch = z.infer<typeof ticketingSearch>;
export type Window = (typeof WINDOWS)[number];
export type Trigger = (typeof TRIGGERS)[number];
