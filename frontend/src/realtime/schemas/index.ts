import { eventSchemas, type RealtimeEvent } from '@/realtime/schemas/events';

export * from '@/realtime/schemas/events';

export type ParsedFrame =
  /** A known type whose payload is valid. */
  | { kind: 'event'; event: RealtimeEvent }
  /** A type this client does not know: skipped without a warning (DOC-33 §2.2, SE-11). */
  | { kind: 'unknown'; type: string }
  /** Not JSON, or a known type with a bad payload. */
  | { kind: 'invalid'; type: string; reason: string };

/**
 * Parses one SSE frame: `name` is its `event:` field, `text` its `data:` line. Never throws. `null` values are
 * dropped before validation, because the publishers may send them for absent fields (DOC-33 §2.1).
 */
export function parseFrame(name: string | undefined, text: string): ParsedFrame {
  let json: unknown;
  try {
    json = JSON.parse(text, (_key, value: unknown) => (value === null ? undefined : value));
  } catch {
    return { kind: 'invalid', type: name ?? '', reason: 'data is not JSON' };
  }
  const type = typeof json === 'object' && json !== null && 'type' in json ? json.type : name;
  if (typeof type !== 'string' || !Object.hasOwn(eventSchemas, type)) {
    return { kind: 'unknown', type: typeof type === 'string' ? type : '' };
  }
  const result = eventSchemas[type as keyof typeof eventSchemas].safeParse(json);
  if (!result.success) {
    return { kind: 'invalid', type, reason: result.error.issues[0]?.message ?? 'does not match the schema' };
  }
  return { kind: 'event', event: result.data };
}
