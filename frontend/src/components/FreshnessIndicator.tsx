import { toneClasses } from '@/components/tone';
import { en } from '@/i18n/en';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDateTime, formatIsoUtc, toMillis } from '@/lib/time';
import { useAxisNow, useRelative, type TimeAxis } from '@/lib/use-now';
import { cn } from '@/lib/utils';

interface FreshnessIndicatorProps {
  /** ISO-8601 instant of the newest data. */
  asOf?: string;
  axis: TimeAxis;
  /** `relative`: "Updated 5 s ago"; `absolute`: "As of Sep 29, 4:05 PM CDT". */
  mode?: 'relative' | 'absolute';
  /** Older than this turns the indicator to warning. */
  staleAfterSeconds?: number;
}

function Dot({ stale }: { stale: boolean }) {
  const tone = toneClasses(stale ? 'warning' : 'success');
  // 7 px dot with a 3 px halo (DOC-35 §5.2).
  const halo = stale ? 'ring-tone-warning-bg' : 'ring-tone-success-bg';
  return <span className={cn('size-[7px] shrink-0 rounded-full ring-[3px]', tone.solid, halo)} aria-hidden="true" />;
}

/** Every live number states its freshness (DOC-34 P-1). */
export function FreshnessIndicator({ asOf, axis, mode = 'relative', staleAfterSeconds }: FreshnessIndicatorProps) {
  const clock = useBusinessClock();
  const wakeAt =
    asOf !== undefined && staleAfterSeconds !== undefined ? toMillis(asOf) + staleAfterSeconds * 1000 + 1 : undefined;
  const now = useAxisNow(asOf, axis, wakeAt);
  const relative = useRelative(asOf ?? new Date(0).toISOString(), axis);

  if (asOf === undefined) {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-muted-foreground">
        <span
          className="size-[7px] shrink-0 rounded-full bg-tone-neutral-solid ring-[3px] ring-tone-neutral-bg"
          aria-hidden="true"
        />
        {en.freshness.noData}
      </span>
    );
  }

  const stale = staleAfterSeconds !== undefined && (now - toMillis(asOf)) / 1000 > staleAfterSeconds;
  const updated =
    mode === 'absolute'
      ? en.time.asOf(formatDateTime(asOf, { timeZone: clock.timezone, now }))
      : en.time.updated(relative);
  return (
    <span
      title={`${en.help.freshness} ${formatIsoUtc(asOf)}`}
      data-stale={stale}
      className={cn(
        'inline-flex items-center gap-2 text-xs tabular-nums',
        stale ? 'text-tone-warning-fg' : 'text-muted-foreground',
      )}
    >
      <Dot stale={stale} />
      {stale ? `${en.time.stale} · ${updated}` : updated}
    </span>
  );
}
