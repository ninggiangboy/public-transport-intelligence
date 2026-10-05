import { z } from 'zod/mini';

// Search params of /scorecard and /scorecard/$routeId (DOC-36 screens/route-scorecard §2). Bad values are dropped, so
// an old link still opens. Apart from the model, so that the route table, which ships with the first paint, does not
// carry the scorecard's code.

const list = <T extends z.ZodMiniType>(item: T) =>
  z.pipe(
    z.transform((value: unknown) => (typeof value === 'string' || typeof value === 'number' ? [value] : value)),
    z.array(item).check(z.maxLength(20)),
  );

const day = z.string().check(z.regex(/^\d{4}-\d{2}-\d{2}$/));
const int = (min: number, max: number) => z.coerce.number().check(z.int(), z.gte(min), z.lte(max));

export const SORTS = ['otp', 'route'] as const;
export const TABS = ['delays', 'profile', 'disruptions'] as const;
export const BUCKETS = ['hour-of-week', 'hour', 'day'] as const;

export const scorecardSearch = z.object({
  from: z.catch(z.optional(day), undefined),
  to: z.catch(z.optional(day), undefined),
  /** GTFS route types; the mode filter writes 3,11 (bus) or 0,1,2 (rail). */
  routeType: z.catch(z.optional(list(int(0, 1700))), undefined),
  sort: z.catch(z.optional(z.enum(SORTS)), undefined),
  /** The route open in the summary drawer. */
  route: z.catch(z.optional(z.string().check(z.minLength(1))), undefined),
});

export const routeScorecardSearch = z.object({
  tab: z.catch(z.optional(z.enum(TABS)), undefined),
  from: z.catch(z.optional(day), undefined),
  to: z.catch(z.optional(day), undefined),
  dir: z.catch(z.optional(int(0, 1)), undefined),
  bucket: z.catch(z.optional(z.enum(BUCKETS)), undefined),
  /** Stop profile: ISO day of week (1 = Monday) and hour of day; default the business clock's. */
  dow: z.catch(z.optional(int(1, 7)), undefined),
  hour: z.catch(z.optional(int(0, 23)), undefined),
  /** The disruption open in the drawer of the Disruptions tab. */
  disruption: z.catch(z.optional(z.string().check(z.minLength(1))), undefined),
});

export type ScorecardSearch = z.infer<typeof scorecardSearch>;
export type RouteScorecardSearch = z.infer<typeof routeScorecardSearch>;
export type Sort = (typeof SORTS)[number];
export type Tab = (typeof TABS)[number];
export type Bucket = (typeof BUCKETS)[number];
