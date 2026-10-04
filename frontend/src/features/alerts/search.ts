import { z } from 'zod/mini';

// Search params of /alerts (DOC-34 §5.2, screens/alert-feed §2). Bad values are dropped, so an old link still opens.

const list = <T extends z.ZodMiniType>(item: T) =>
  z.pipe(
    z.transform((value: unknown) => (typeof value === 'string' || typeof value === 'number' ? [value] : value)),
    z.array(item).check(z.maxLength(20)),
  );

export const ALERT_TYPES = [
  'DISRUPTION',
  'BUNCHING',
  'TICKETING_ANOMALY',
  'DLQ_SEVERE',
  'FEED_STALE',
  'INFRA',
] as const;
export const AUDIENCES = ['PUBLIC', 'OPERATIONS', 'ENGINEERING'] as const;
export const STATES = ['open', 'unacknowledged', 'all'] as const;
export const WINDOWS = ['24h', '7d'] as const;

export const alertsSearch = z.object({
  state: z.catch(z.optional(z.enum(STATES)), undefined),
  audience: z.catch(z.optional(list(z.enum(AUDIENCES))), undefined),
  type: z.catch(z.optional(list(z.enum(ALERT_TYPES))), undefined),
  severity: z.catch(z.optional(list(z.coerce.number().check(z.int(), z.gte(0), z.lte(2)))), undefined),
  route: z.catch(z.optional(list(z.string().check(z.minLength(1)))), undefined),
  window: z.catch(z.optional(z.enum(WINDOWS)), undefined),
  /** The alert open in the detail panel. */
  alert: z.catch(z.optional(z.string().check(z.minLength(1))), undefined),
});

export type AlertsSearch = z.infer<typeof alertsSearch>;
export type AlertState = (typeof STATES)[number];
export type AlertWindow = (typeof WINDOWS)[number];
