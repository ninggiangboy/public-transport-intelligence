import { z } from 'zod/mini';

// Search params of /overview (DOC-36 screens/overview §2). Apart from the model, so that the route table, which ships
// with the first paint, does not carry the Overview's code.

export const PERIODS = ['1d', '7d', '30d'] as const;
export type Period = (typeof PERIODS)[number];

export const overviewSearch = z.object({ period: z.catch(z.optional(z.enum(PERIODS)), undefined) });
