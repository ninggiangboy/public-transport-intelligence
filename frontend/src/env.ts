import { z } from 'zod/mini';

// Runtime configuration rendered into /env.js by the nginx entrypoint (DOC-34 §10, DOC-29 §3.5). One image serves
// every environment, so nothing here is baked in at build time.

const nonEmpty = z.string().check(z.minLength(1));

const schema = z.object({
  /** Keycloak as the browser reaches it; empty hides "Sign in" and everyone is anonymous. */
  keycloakUrl: z._default(z.union([z.url({ protocol: /^https?$/ }), z.literal('')]), ''),
  keycloakRealm: z._default(nonEmpty, 'pti'),
  keycloakClientId: z._default(nonEmpty, 'pti-web'),
  mapStyle: z._default(z.enum(['offline', 'online']), 'offline'),
  demoControl: z._default(z.boolean(), false),
});

export type AppEnv = z.infer<typeof schema>;

/** Anonymous, offline map, no Demo control (UX-07). */
export const SAFE_DEFAULTS: AppEnv = schema.parse({});

declare global {
  interface Window {
    __PTI_ENV__?: unknown;
  }
}

/** Reads and validates `window.__PTI_ENV__`; missing or invalid configuration falls back to {@link SAFE_DEFAULTS}. */
export function readEnv(raw: unknown = globalThis.window.__PTI_ENV__): AppEnv {
  const result = schema.safeParse(raw ?? {});
  if (result.success) return result.data;
  console.warn('Invalid window.__PTI_ENV__, using safe defaults', z.flattenError(result.error).fieldErrors);
  return SAFE_DEFAULTS;
}

export const env: AppEnv = readEnv();
