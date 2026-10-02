import { formatIsoUtc } from '@/lib/time';
import { useRelative, type TimeAxis } from '@/lib/use-now';

interface RelativeTimeProps {
  /** ISO-8601 instant. */
  at: string;
  /** `event`: relative to `businessNow`; `audit`: relative to the machine clock (DOC-34 §8). */
  axis: TimeAxis;
}

/** "12 s ago"; re-renders every second under a minute and every 30 s after (DOC-35 §5.2). */
export function RelativeTime({ at, axis }: RelativeTimeProps) {
  const text = useRelative(at, axis);
  return (
    <time dateTime={at} title={formatIsoUtc(at)} className="tabular-nums">
      {text}
    </time>
  );
}
