import { z } from 'zod/mini';

// Search params of /ops/jobs (DOC-36 screens/ops-console-jobs §2). Bad values are dropped, so an old link still opens.
// Apart from the model, so that the route table, which ships with the first paint, does not carry the screen's code.

const list = <T extends z.ZodMiniType>(item: T) =>
  z.pipe(
    z.transform((value: unknown) => (typeof value === 'string' ? [value] : value)),
    z.array(item).check(z.maxLength(20)),
  );

const instant = z.string().check(z.refine((value) => !Number.isNaN(Date.parse(value))));

export const WINDOWS = ['15m', '1h', '6h', '24h'] as const;
export const BUCKETS = ['1m', '5m', '15m', '1h'] as const;
export const KINDS = ['BATCH_JOB', 'STREAM'] as const;

export const pipelineSearch = z.object({
  window: z.catch(z.optional(z.enum(WINDOWS)), undefined),
  /** A fixed range; when both are set, `window` is ignored (at most 24 hours). */
  from: z.catch(z.optional(instant), undefined),
  to: z.catch(z.optional(instant), undefined),
  kind: z.catch(z.optional(z.enum(KINDS)), undefined),
  status: z.catch(z.optional(list(z.string().check(z.regex(/^[A-Z_]{1,30}$/)))), undefined),
  name: z.catch(z.optional(list(z.string().check(z.minLength(1), z.maxLength(100)))), undefined),
  bucket: z.catch(z.optional(z.enum(BUCKETS)), undefined),
  /** The run open in the drawer. */
  run: z.catch(z.optional(z.string().check(z.minLength(1))), undefined),
});

export type PipelineSearch = z.infer<typeof pipelineSearch>;
export type Window = (typeof WINDOWS)[number];
export type Bucket = (typeof BUCKETS)[number];
export type Kind = (typeof KINDS)[number];
