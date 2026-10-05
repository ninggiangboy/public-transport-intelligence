import { Download } from 'lucide-react';
import { useId, type ReactNode } from 'react';

import type { components } from '@/api/generated/schema';
import { Callout } from '@/components/Callout';
import { SegmentedControl } from '@/components/SegmentedControl';
import { TimeRangePicker } from '@/components/TimeRangePicker';
import { toneClasses, type Tone } from '@/components/tone';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
  MAX_DAYS,
  presetOf,
  presetRange,
  PRESETS,
  tolerances,
  type DayRange,
  type Preset,
} from '@/features/scorecard/model';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDelaySeconds, formatDuration } from '@/lib/format';
import { formatDate, formatDateTime } from '@/lib/time';
import { cn } from '@/lib/utils';

type OtpItem = components['schemas']['Item'];

const copy = scorecardCopy.scorecard;
const DAY_SECONDS = 86_400;

/** "Week" / "Month" and the date range; both write `from` and `to` to the URL (§4). */
export function RangeControls({
  range,
  yesterday,
  onChange,
  onExport,
}: {
  range: DayRange;
  yesterday: string;
  onChange: (range: DayRange) => void;
  onExport?: () => void;
}) {
  const preset = presetOf(range, yesterday);
  return (
    <>
      <SegmentedControl<Preset | ''>
        label={copy.preset.label}
        value={preset ?? ''}
        options={PRESETS.map((value) => ({ value, label: copy.preset[value] }))}
        onChange={(value) => {
          if (value) onChange(presetRange(value, yesterday));
        }}
      />
      <TimeRangePicker
        value={{ from: range.from, to: range.to }}
        presets={[]}
        maxRangeSeconds={MAX_DAYS * DAY_SECONDS}
        granularity="date"
        maxDate={yesterday}
        onChange={(value) => {
          if (value.from && value.to) onChange({ from: value.from, to: value.to });
        }}
      />
      {onExport ? (
        <Button variant="outline" onClick={onExport}>
          <Download aria-hidden="true" />
          {copy.export}
        </Button>
      ) : null}
    </>
  );
}

/** "On time means no more than 5 min early or 5 min late · data through …", or the mixed-window warning (§4). */
export function ThresholdNote({ items, asOf }: { items: readonly OtpItem[]; asOf: string | undefined }) {
  const clock = useBusinessClock();
  const window = tolerances(items);
  const through = asOf ? copy.dataThrough(formatDateTime(asOf, { timeZone: clock.timezone })) : undefined;
  if (window?.mixed) {
    return (
      <span className="inline-flex flex-wrap items-center gap-x-1">
        <Tooltip>
          <TooltipTrigger asChild>
            <span tabIndex={0} className={cn('underline decoration-dotted', toneClasses('warning').text)}>
              {copy.mixed}
            </span>
          </TooltipTrigger>
          <TooltipContent>{copy.mixedHelp}</TooltipContent>
        </Tooltip>
        {through ? <span>· {through}</span> : null}
      </span>
    );
  }
  const note = window ? copy.note(formatDuration(window.early * 1000), formatDuration(window.late * 1000)) : undefined;
  return <span>{[note, through].filter(Boolean).join(' · ')}</span>;
}

/** The info band of a range cut to 31 days (UC-06 2a). */
export function ClampedNotice({ range }: { range: DayRange }) {
  return (
    <Callout tone="info">
      {copy.clamped(formatDate(`${range.from}T12:00:00Z`, 'UTC'), formatDate(`${range.to}T12:00:00Z`, 'UTC'))}
    </Callout>
  );
}

/** Three numbers side by side, split by hairlines. */
export function StatRow({ stats }: { stats: { label: string; value: ReactNode; tone?: Tone }[] }) {
  return (
    <dl className="grid grid-cols-3 overflow-hidden rounded-lg border border-border bg-card shadow-xs">
      {stats.map((stat, index) => (
        <div key={stat.label} className={cn('px-3.5 py-3', index > 0 && 'border-l border-border')}>
          <dt className="text-xs text-muted-foreground">{stat.label}</dt>
          <dd
            className={cn(
              'mt-1 text-[22px] leading-tight font-semibold tracking-[-0.035em] tabular-nums',
              stat.tone ? toneClasses(stat.tone).text : 'text-foreground',
            )}
          >
            {stat.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * The typical delay at a stop as a bar (average) with a tick (90th percentile) on a scale shared by the stops of one
 * list (§4). Early arrivals draw no bar.
 */
export function StopDelayBar({
  label,
  average,
  p90,
  max,
}: {
  label: string;
  average: number;
  p90: number | undefined;
  max: number;
}) {
  const scale = (value: number) => `${Math.min(100, (Math.max(value, 0) / max) * 100)}%`;
  const tone: Tone = average >= 300 ? 'danger' : average >= 60 ? 'warning' : 'success';
  return (
    <span
      role="img"
      aria-label={copy.drawer.stopDelay(
        label,
        formatDelaySeconds(average),
        p90 === undefined ? en.kv.empty : formatDelaySeconds(p90),
      )}
      className="relative block h-2 rounded-full bg-muted"
    >
      <span
        className={cn('absolute inset-y-0 left-0 rounded-full', toneClasses(tone).solid)}
        style={{ width: scale(average) }}
      />
      {p90 === undefined ? null : (
        <span className="absolute -inset-y-1 w-0.5 rounded-full bg-foreground" style={{ left: scale(p90) }} />
      )}
    </span>
  );
}

/** A labelled native <select>: weekday, hour (§6). */
export function SelectField<V extends string | number>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: V;
  options: { value: V; label: string }[];
  onChange: (value: V) => void;
}) {
  const id = useId();
  return (
    <span className="inline-flex items-center gap-2">
      <label htmlFor={id} className="text-label font-medium text-muted-foreground">
        {label}
      </label>
      <select
        id={id}
        value={String(value)}
        onChange={(event) => {
          const next = options.find((option) => String(option.value) === event.target.value);
          if (next) onChange(next.value);
        }}
        className="h-8.5 rounded-md border border-input bg-card px-2.5 text-sm text-foreground shadow-xs focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        {options.map((option) => (
          <option key={String(option.value)} value={String(option.value)}>
            {option.label}
          </option>
        ))}
      </select>
    </span>
  );
}
