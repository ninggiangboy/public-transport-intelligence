import { useBusinessClock } from '@/lib/business-clock';
import { formatDate, formatDateTime, formatIsoUtc, formatTime } from '@/lib/time';

interface TimestampProps {
  /** ISO-8601 instant. */
  at: string;
  format?: 'time' | 'datetime' | 'date';
  /** Appends "CDT" / "CST". Default true. */
  showZone?: boolean;
  /** Ops tables show seconds. */
  seconds?: boolean;
}

/**
 * An absolute time in the agency's zone (DOC-37 §4.2). Its `title` is the full ISO UTC instant so that operators can
 * match it against logs (DOC-35 §5.2).
 */
export function Timestamp({ at, format = 'datetime', showZone = true, seconds = false }: TimestampProps) {
  const clock = useBusinessClock();
  const timeZone = clock.timezone;
  const text =
    format === 'date'
      ? formatDate(at, timeZone)
      : format === 'time'
        ? formatTime(at, { timeZone, showZone, seconds })
        : formatDateTime(at, { timeZone, showZone, seconds, now: clock.now() });
  return (
    <time dateTime={at} title={formatIsoUtc(at)} className="tabular-nums">
      {text}
    </time>
  );
}
