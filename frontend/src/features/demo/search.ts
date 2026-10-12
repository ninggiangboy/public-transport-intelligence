import { z } from 'zod/mini';

// Search params of /ops/demo (DOC-36 screens/demo-control §2): `run` opens the drawer of a scenario run. The form is
// not written to the URL. Parsed by the screen rather than the route, so that the route table does not carry it (DR-110).

export const demoSearch = z.object({
  run: z.catch(z.optional(z.string().check(z.minLength(1), z.maxLength(64))), undefined),
});

export type DemoSearch = z.infer<typeof demoSearch>;
