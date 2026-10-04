import { ConfidenceChip } from '@/components/ConfidenceChip';
import { RouteBadge } from '@/components/RouteBadge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { confidenceOf, etaDisplay, expectedAt, minutesUntil, type Arrival } from '@/features/stops/arrivals';
import { en } from '@/i18n/en';
import { formatPassengerDelay } from '@/lib/format';
import { formatTime, toMillis } from '@/lib/time';
import { cn } from '@/lib/utils';

interface RouteLook {
  displayName: string;
  color?: string;
  textColor?: string;
}

interface ArrivalRowProps {
  arrival: Arrival;
  route: RouteLook;
  /** businessNow in epoch ms (DOC-34 §8). */
  now: number;
  timeZone: string;
}

function delayTone(seconds: number): string {
  if (Math.abs(seconds) < 60) return 'text-tone-success-fg';
  return seconds > 0 ? 'text-tone-warning-fg' : 'text-tone-info-fg';
}

/**
 * One departure (DOC-36 screens/stop-detail §4): route shield and headsign; whether the time is live or schedule only;
 * the delay and the confidence of the prediction; the ETA large on the right with the clock time under it.
 */
export function ArrivalRow({ arrival, route, now, timeZone }: ArrivalRowProps) {
  const copy = en.stops.departures;
  const level = confidenceOf(arrival);
  const schedule = level === 'NONE';
  const at = schedule ? arrival.scheduledArrival : expectedAt(arrival);
  const eta = etaDisplay(at, now, timeZone, schedule);
  const time = formatTime(at, { timeZone, showZone: false });
  const live = arrival.realtimeArrival !== undefined;
  const delay = arrival.predictedDelaySeconds;
  const scheduledDiffers = !schedule && toMillis(arrival.scheduledArrival) !== toMillis(at);
  const minutes = minutesUntil(at, now);

  const label = copy.rowLabel({
    route: route.displayName,
    headsign: arrival.headsign,
    eta: copy.etaSpoken(minutes),
    time,
    delay: schedule ? copy.scheduled : copy.delaySpoken(delay),
    confidence: copy.confidenceSpoken(level, arrival.sampleCount),
  });

  return (
    <li
      aria-label={label}
      className="grid grid-cols-[42px_minmax(0,1fr)_auto] items-center gap-3.5 border-t border-border px-4 py-3.5 first:border-t-0 hover:bg-surface md:grid-cols-[44px_minmax(0,1fr)_150px_112px] md:px-4.5 md:py-3.25"
    >
      <RouteBadge
        routeId={arrival.routeId}
        displayName={route.displayName}
        color={route.color}
        textColor={route.textColor}
        size="lg"
      />
      <div className="min-w-0" aria-hidden="true">
        <p className="truncate text-[15.5px] font-medium tracking-[-0.012em] md:text-[14.5px]">
          {arrival.headsign ?? route.displayName}
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
          {live ? (
            <Tooltip>
              <TooltipTrigger asChild>
                <span tabIndex={-1} className="inline-flex items-center gap-1.5 text-tone-success-fg">
                  <span className="size-1.5 rounded-full bg-tone-success-solid ring-[3px] ring-tone-success-bg" />
                  {copy.live}
                </span>
              </TooltipTrigger>
              <TooltipContent>{en.stops.help.realtime}</TooltipContent>
            </Tooltip>
          ) : null}
          {schedule ? <span>{copy.scheduleOnly}</span> : null}
          {/* Phones have no middle column: the delay joins this line. */}
          {schedule ? null : <span className={cn('md:hidden', delayTone(delay))}>{formatPassengerDelay(delay)}</span>}
        </div>
      </div>
      <div className="hidden md:block" aria-hidden="true">
        {schedule ? (
          <p className="text-label font-medium text-muted-foreground">{copy.scheduled}</p>
        ) : (
          <p className={cn('text-label font-medium', delayTone(delay))}>{formatPassengerDelay(delay)}</p>
        )}
        <div className="mt-1">
          <ConfidenceChip level={level} sampleCount={arrival.sampleCount} />
        </div>
      </div>
      <div className="text-right" aria-hidden="true">
        <p className="text-[28px] leading-none font-semibold tracking-[-0.045em] tabular-nums md:text-[26px]">
          {eta.kind === 'due' ? (
            en.format.due
          ) : eta.kind === 'minutes' ? (
            <>
              {eta.minutes}
              <small className="ml-0.5 text-sm font-medium tracking-normal text-muted-foreground">
                {en.format.unit.min}
              </small>
            </>
          ) : (
            <>
              {eta.time}
              <small className="ml-0.5 text-sm font-medium tracking-normal text-muted-foreground">{eta.unit}</small>
            </>
          )}
        </p>
        <p className="mt-1 text-xs text-muted-foreground tabular-nums">
          {eta.kind === 'time' ? en.time.in(minutes, en.format.unit.min) : time}
        </p>
        {scheduledDiffers ? (
          <p className="hidden text-xs whitespace-nowrap text-muted-foreground tabular-nums md:block">
            {copy.scheduledAt(formatTime(arrival.scheduledArrival, { timeZone, showZone: false }))}
          </p>
        ) : null}
      </div>
    </li>
  );
}
