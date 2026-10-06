import { z } from 'zod/mini';

// Search params of /ops/dlq (DOC-36 screens/ops-console-dlq §2). Bad values are dropped, so an old link still opens.
// Apart from the model, so that the route table, which ships with the first paint, does not carry the screen's code.

/** A value that is dropped when it does not parse, so that an old link still opens. */
const loose = <T extends z.ZodMiniType>(schema: T) => z.catch(z.optional(schema), undefined);
/** One value or a list of at most 20 (one value comes back from the URL as a plain string or number, src/lib/url.ts). */
const listOf = <T extends z.ZodMiniType>(item: T) =>
  loose(
    z.pipe(
      z.transform((value: unknown) => (typeof value === 'string' || typeof value === 'number' ? [value] : value)),
      z.array(item).check(z.maxLength(20)),
    ),
  );
const instant = z.string().check(z.refine((value) => !Number.isNaN(Date.parse(value))));
const pattern = (regex: RegExp) => z.string().check(z.regex(regex));
const upper = pattern(/^[A-Z][A-Z0-9_]{0,40}$/);

export const TABS = ['review', 'confirm', 'closed', 'actions'] as const;
export const ACTOR_TYPES = ['auto', 'system', 'user'] as const;
export const ACTION_WINDOWS = ['24h', '7d'] as const;

export const dlqSearch = z.object({
  tab: loose(z.enum(TABS)),
  status: listOf(upper),
  source: listOf(upper),
  stage: listOf(upper),
  category: listOf(pattern(/^[a-z_]{1,40}$/)),
  severity: listOf(z.coerce.number().check(z.int(), z.gte(0), z.lte(2))),
  rule: listOf(pattern(/^DQ-\d{2}$/)),
  from: loose(instant),
  to: loose(instant),
  /** The dead letter open in the detail panel. */
  id: loose(z.string().check(z.minLength(1), z.maxLength(64))),
  action: listOf(upper),
  actor: loose(z.enum(ACTOR_TYPES)),
  window: loose(z.enum(ACTION_WINDOWS)),
});

export type DlqSearch = z.infer<typeof dlqSearch>;
export type DlqTab = (typeof TABS)[number];
export type ActorType = (typeof ACTOR_TYPES)[number];
export type ActionWindow = (typeof ACTION_WINDOWS)[number];
