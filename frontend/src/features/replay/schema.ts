import { z } from 'zod/mini';

import { SOURCES, type ReplaySource } from '@/features/replay/search';
import { replayCopy } from '@/i18n/replay';

// The form of a raw zone replay (DOC-36 screens/ops-console-replay §4). It repeats the rules of the API (DOC-22 §4.1)
// to answer at once; the API still decides. Times are Kafka record times on the real clock, not business time.

const MINUTE_MS = 60_000;
const DAY_MS = 24 * 60 * MINUTE_MS;
/** `pti.replay.raw-settle`: the S3 sink closes its files within 5 minutes, so the range must end 10 minutes ago. */
export const SETTLE_MS = 10 * MINUTE_MS;
/** `pti.replay.raw-max-age`: the raw zone keeps 30 days; a replay starts within 29. */
export const MAX_AGE_MS = 29 * DAY_MS;
export const MAX_RANGE_MS = 7 * DAY_MS;

export interface ReplayFormValues {
  source: ReplaySource | undefined;
  /** ISO instants; empty while the input is incomplete. */
  fromTs: string;
  toTs: string;
  recomputeAnalytics: boolean;
}

export type ReplayField = 'source' | 'fromTs' | 'toTs';

const instant = z.string().check(z.refine((value) => !Number.isNaN(Date.parse(value))));

/** The schema of the form for the real clock `now` (epoch ms); its issues carry the field as path and the copy of §4. */
export function rawReplayForm(now: number) {
  const errors = replayCopy.errors;
  return z
    .object({
      source: z.enum(SOURCES),
      fromTs: instant,
      toTs: instant,
      recomputeAnalytics: z.boolean(),
    })
    .check(
      z.superRefine((values, ctx) => {
        const from = Date.parse(values.fromTs);
        const to = Date.parse(values.toTs);
        const issue = (path: ReplayField, message: string) => {
          ctx.issues.push({ code: 'custom', message, path: [path], input: values });
        };
        if (from < now - MAX_AGE_MS) issue('fromTs', errors.maxAge);
        if (from >= to) issue('toTs', errors.order);
        else if (to - from > MAX_RANGE_MS) issue('toTs', errors.maxRange);
        if (to > now - SETTLE_MS) issue('toTs', errors.settle);
      }),
    );
}

export type RawReplayBody = z.infer<ReturnType<typeof rawReplayForm>>;

export type FieldErrors = Partial<Record<ReplayField, string>>;

/** The valid body, or the first message per field. A field left empty has no message: the button stays disabled. */
export function checkReplayForm(
  values: ReplayFormValues,
  now: number,
): { ok: true; body: RawReplayBody } | { ok: false; errors: FieldErrors } {
  const result = rawReplayForm(now).safeParse(values);
  if (result.success) return { ok: true, body: result.data };
  const errors: FieldErrors = {};
  const rules: readonly string[] = Object.values(replayCopy.errors);
  for (const issue of result.error.issues) {
    const field = issue.path[0] as ReplayField;
    // Missing or incomplete values are not errors to show; the rules of §4 are.
    if (!rules.includes(issue.message) || errors[field] !== undefined) continue;
    errors[field] = issue.message;
  }
  return { ok: false, errors };
}
