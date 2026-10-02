import { useId, useState } from 'react';

import { FilterChip } from '@/components/FilterChip';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { en } from '@/i18n/en';
import { formatDuration } from '@/lib/format';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDate, formatDateTime, fromLocalInput, presetSeconds, toLocalInput } from '@/lib/time';

export interface TimeRangeValue {
  /** A preset such as "1h"; when set, `from` and `to` are not. */
  window?: string;
  /** ISO-8601 instant for `minute` granularity, "YYYY-MM-DD" for `date`. */
  from?: string;
  to?: string;
}

interface TimeRangePickerProps {
  value: TimeRangeValue;
  /** For example `['15m', '1h', '6h', '24h']`. */
  presets: string[];
  /** Mirrors the API limit of the endpoint (DOC-32). */
  maxRangeSeconds: number;
  granularity: 'minute' | 'date';
  onChange: (value: TimeRangeValue) => void;
}

/** Preset buttons plus a custom range in the agency's zone (DOC-35 §5.3). The range never exceeds `maxRangeSeconds`. */
export function TimeRangePicker({ value, presets, maxRangeSeconds, granularity, onChange }: TimeRangePickerProps) {
  const clock = useBusinessClock();
  const timeZone = clock.timezone;
  const inputType = granularity === 'date' ? 'date' : 'datetime-local';
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);
  const fromId = useId();
  const toId = useId();

  const toInput = (stored: string | undefined) => {
    if (!stored) return '';
    return granularity === 'date' ? stored : toLocalInput(stored, timeZone);
  };
  const toMs = (input: string) =>
    granularity === 'date' ? Date.parse(`${input}T00:00:00Z`) : fromLocalInput(input, timeZone);

  const openChange = (next: boolean) => {
    if (next) {
      setFrom(toInput(value.from));
      setTo(toInput(value.to));
      setError(undefined);
    }
    setOpen(next);
  };

  const apply = () => {
    const fromMs = from ? toMs(from) : Number.NaN;
    const toMsValue = to ? toMs(to) : Number.NaN;
    if (!from || !to) {
      setError(en.timeRange.required);
    } else if (Number.isNaN(fromMs) || Number.isNaN(toMsValue) || fromMs >= toMsValue) {
      setError(en.timeRange.invalid);
    } else if ((toMsValue - fromMs) / 1000 > maxRangeSeconds) {
      setError(en.timeRange.tooLong(formatDuration(maxRangeSeconds * 1000)));
    } else {
      onChange(
        granularity === 'date'
          ? { from, to }
          : { from: new Date(fromMs).toISOString(), to: new Date(toMsValue).toISOString() },
      );
      setOpen(false);
    }
  };

  const allowed = presets.filter((preset) => presetSeconds(preset) <= maxRangeSeconds);
  const custom = value.window === undefined && value.from !== undefined && value.to !== undefined;
  const customText =
    custom && value.from && value.to
      ? granularity === 'date'
        ? `${formatDate(`${value.from}T12:00:00Z`, 'UTC')} – ${formatDate(`${value.to}T12:00:00Z`, 'UTC')}`
        : `${formatDateTime(value.from, { timeZone, showZone: false })} – ${formatDateTime(value.to, { timeZone })}`
      : en.timeRange.custom;

  return (
    <div className="flex flex-wrap items-center gap-2">
      <SegmentedControl
        label={en.timeRange.label}
        size="sm"
        options={allowed.map((preset) => ({ value: preset, label: preset }))}
        value={value.window ?? ''}
        onChange={(window) => {
          onChange({ window });
        }}
      />
      <Popover open={open} onOpenChange={openChange}>
        <PopoverTrigger asChild>
          <FilterChip active={custom} text={customText} />
        </PopoverTrigger>
        <PopoverContent aria-label={en.timeRange.custom} className="w-72">
          <div className="flex flex-col gap-3 p-1">
            <div className="flex flex-col gap-1">
              <label htmlFor={fromId} className="text-label font-medium text-foreground-2">
                {en.timeRange.from}
              </label>
              <Input
                id={fromId}
                type={inputType}
                value={from}
                onChange={(event) => {
                  setFrom(event.target.value);
                }}
              />
            </div>
            <div className="flex flex-col gap-1">
              <label htmlFor={toId} className="text-label font-medium text-foreground-2">
                {en.timeRange.to}
              </label>
              <Input
                id={toId}
                type={inputType}
                value={to}
                onChange={(event) => {
                  setTo(event.target.value);
                }}
              />
            </div>
            {error ? (
              <p role="alert" className="text-xs text-tone-danger-fg">
                {error}
              </p>
            ) : null}
            <Button size="sm" onClick={apply}>
              {en.timeRange.apply}
            </Button>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
