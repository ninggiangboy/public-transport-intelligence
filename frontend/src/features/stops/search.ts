import { z } from 'zod/mini';

// Search params of /stops and /stops/$stopId (DOC-34 §5.1, §5.2). A value of the wrong type or out of range is
// dropped, so an old or hand-edited link still opens.

/** A list param: one value comes back from the URL as a plain string (src/lib/url.ts). */
const list = z.pipe(
  z.transform((value: unknown) => (typeof value === 'string' ? [value] : value)),
  z.array(z.string().check(z.minLength(1))).check(z.maxLength(20)),
);

export const findStopSearch = z.object({
  q: z.catch(z.optional(z.string().check(z.trim(), z.minLength(2), z.maxLength(100))), undefined),
});

export const stopDetailSearch = z.object({
  /** Routes the departures are filtered to; none = all. */
  route: z.catch(z.optional(list), undefined),
  /** Direction, only when the stop is served in both. */
  dir: z.catch(z.optional(z.coerce.number().check(z.int(), z.gte(0), z.lte(1))), undefined),
});

export type FindStopSearch = z.infer<typeof findStopSearch>;
export type StopDetailSearch = z.infer<typeof stopDetailSearch>;
