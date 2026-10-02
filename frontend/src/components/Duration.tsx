import { en } from '@/i18n/en';
import { formatDuration, formatIsoDuration } from '@/lib/format';

interface DurationProps {
  ms?: number;
  /** ISO-8601 duration from the API ("PT20M"). */
  iso?: string;
}

/** "4 min 12 s" (DOC-37 §4.5). Shows a dash when neither value is given. */
export function Duration({ ms, iso }: DurationProps) {
  const text = ms !== undefined ? formatDuration(ms) : iso !== undefined ? formatIsoDuration(iso) : en.kv.empty;
  return <span className="tabular-nums">{text}</span>;
}
