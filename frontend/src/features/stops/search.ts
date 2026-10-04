import { z } from 'zod';

// Search params of /stops and /stops/$stopId (DOC-34 §5.1, §5.2). A value of the wrong type or out of range is
// dropped, so an old or hand-edited link still opens.

const list = z.preprocess((value) => (typeof value === 'string' ? [value] : value), z.array(z.string().min(1)).max(20));

export const findStopSearch = z.object({
  q: z.string().trim().min(2).max(100).optional().catch(undefined),
});

export const stopDetailSearch = z.object({
  /** Routes the departures are filtered to; none = all. */
  route: list.optional().catch(undefined),
  /** Direction, only when the stop is served in both. */
  dir: z.coerce.number().int().min(0).max(1).optional().catch(undefined),
});

export type FindStopSearch = z.infer<typeof findStopSearch>;
export type StopDetailSearch = z.infer<typeof stopDetailSearch>;
