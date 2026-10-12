import { useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { Ellipsis } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';

import { keys } from '@/api/keys';
import { putRate, simSlug, type SimStatus } from '@/api/sim';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { ErrorState } from '@/components/ErrorState';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Skeleton } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
  formatOffset,
  gtfsRtPerSecond,
  loadRampRunning,
  pausedStreams,
  rateAllowed,
  rateChoice,
  RATE_PRESETS,
  type RateChoice,
} from '@/features/demo/model';
import { demoCopy } from '@/i18n/demo';
import { describeError } from '@/lib/problem-copy';
import { formatCount } from '@/lib/format';
import { notify } from '@/lib/notify';
import { formatDateTime, formatServiceDate } from '@/lib/time';

const copy = demoCopy.simulator;
const speed = demoCopy.speed;
const DECIMAL = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 });

interface Rate {
  gtfsRt?: number;
  ticketing?: number;
}
const RATE_FIELDS = ['gtfsRt', 'ticketing'] as const;
type RateField = (typeof RATE_FIELDS)[number];

/** The "Simulator" band (§4): state, business time, vehicles, throughput, and "Speed". Its own error state (§7). */
export function SimulatorStrip({ status }: { status: UseQueryResult<SimStatus> }) {
  if (status.isPending) {
    return (
      <section
        aria-label={copy.label}
        aria-busy="true"
        className="rounded-lg border border-border bg-card px-4 py-3.5 shadow-sm"
      >
        <Skeleton className="h-10 w-full" />
      </section>
    );
  }
  if (!status.data) {
    return (
      <section aria-label={copy.label} className="rounded-lg border border-border bg-card shadow-sm">
        <ErrorState error={status.error} variant="block" panel={copy.panel} onRetry={() => void status.refetch()} />
      </section>
    );
  }
  return <Strip status={status.data} />;
}

function Cell({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 border-l border-border px-4 first:border-l-0 first:pl-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-base font-semibold tracking-title tabular-nums">{children}</dd>
    </div>
  );
}

function Unit({ children }: { children: ReactNode }) {
  return <span className="text-xs font-medium text-muted-foreground">{children}</span>;
}

function Strip({ status }: { status: SimStatus }) {
  const paused = status.rate.gtfsRt === 0 && status.rate.ticketing === 0;
  const offset = formatOffset(status.clock.offset);
  const zone = status.clock.agencyTimeZone;
  return (
    <section
      aria-label={copy.label}
      className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 rounded-lg border border-border bg-card px-4 py-3.5 shadow-sm"
    >
      <dl className="flex flex-wrap items-center gap-y-2">
        <Cell label={copy.label}>
          <span className="inline-flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className={
                paused ? 'size-2 rounded-full bg-tone-neutral-solid' : 'size-2 rounded-full bg-tone-success-solid'
              }
            />
            {paused ? copy.paused : copy.running}
          </span>
        </Cell>
        <Cell label={copy.businessTime}>
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={0} className="rounded-sm">
                {formatDateTime(status.clock.businessNow, { timeZone: zone, showZone: false })}
                {offset ? (
                  <>
                    {' '}
                    <Unit>{offset}</Unit>
                  </>
                ) : null}
              </span>
            </TooltipTrigger>
            <TooltipContent>
              <p className="font-medium">{copy.serviceDates}</p>
              {status.clock.serviceDates.map((date) => (
                <p key={date.realDate}>
                  {copy.serviceDate(
                    formatServiceDate(date.realDate),
                    date.feedDate ? formatServiceDate(date.feedDate) : copy.noFeedDate,
                  )}
                </p>
              ))}
            </TooltipContent>
          </Tooltip>
        </Cell>
        <Cell label={copy.vehicles}>{formatCount(status.activeVehicles)}</Cell>
        <Cell label={copy.gtfsRt}>
          {DECIMAL.format(gtfsRtPerSecond(status))} <Unit>{copy.msgPerSecond}</Unit>
        </Cell>
        <Cell label={copy.ticketing}>
          {DECIMAL.format(status.ticketingPerSecond)} <Unit>{copy.salesPerSecond}</Unit>
        </Cell>
      </dl>
      <Speed status={status} />
    </section>
  );
}

function presetLabel(choice: RateChoice): string {
  if (choice === 'custom') return speed.custom;
  return choice === '0' ? speed.paused : speed.multiplier(choice);
}

/** "Speed" (§4, §6): a preset sets both multipliers; "…" sets them apart. Disabled while a load ramp runs. */
function Speed({ status }: { status: SimStatus }) {
  const queryClient = useQueryClient();
  const locked = loadRampRunning(status);
  const choice = rateChoice(status.rate);
  const [pending, setPending] = useState<Rate | undefined>(undefined);
  const [customOpen, setCustomOpen] = useState(false);
  const [customErrors, setCustomErrors] = useState<Partial<Record<RateField, string>>>({});

  /** Sends the change; resolves false when the API refused it with a message shown here. */
  const send = async (rate: Rate, where: 'segmented' | 'custom'): Promise<boolean> => {
    try {
      const next = await putRate(rate);
      queryClient.setQueryData(keys.sim.status(), next);
      notify.success(speed.updated);
      return true;
    } catch (error: unknown) {
      const slug = simSlug(error);
      if (slug === 'load-ramp-running') {
        notify.error(speed.loadRampRunning);
        void queryClient.invalidateQueries({ queryKey: keys.sim.status() });
        return false;
      }
      if (slug === 'invalid-param' && where === 'custom') {
        const described = describeError(error);
        const errors: Partial<Record<RateField, string>> = {};
        for (const item of described.fieldErrors) {
          if (item.field === 'gtfsRt' || item.field === 'ticketing') errors[item.field] = item.message;
        }
        if (Object.keys(errors).length > 0) {
          setCustomErrors(errors);
          return false;
        }
      }
      notify.error(describeError(error).title);
      return false;
    }
  };

  /** A change that stops a stream asks first (§6); others go at once. */
  const change = async (rate: Rate, where: 'segmented' | 'custom') => {
    if (pausedStreams(status.rate, rate)) {
      setPending(rate);
      return true;
    }
    return send(rate, where);
  };

  const options = [
    ...RATE_PRESETS.map((value) => ({ value, label: presetLabel(value) })),
    ...(choice === 'custom' ? [{ value: 'custom' as const, label: speed.custom }] : []),
  ];
  const stream = pending ? pausedStreams(status.rate, pending) : undefined;

  const control = (
    <div className="flex items-center gap-1">
      <SegmentedControl<RateChoice>
        label={speed.label}
        size="sm"
        value={choice}
        disabled={locked}
        options={options}
        onChange={(value) => {
          if (value === 'custom') return;
          const multiplier = Number(value);
          void change({ gtfsRt: multiplier, ticketing: multiplier }, 'segmented');
        }}
      />
      <Popover
        open={customOpen}
        onOpenChange={(open) => {
          setCustomOpen(open);
          setCustomErrors({});
        }}
      >
        <PopoverTrigger asChild>
          <Button variant="ghost" size="icon-sm" disabled={locked} aria-label={speed.more}>
            <Ellipsis aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-64">
          <CustomRate
            rate={status.rate}
            errors={customErrors}
            onErrors={setCustomErrors}
            onApply={async (rate) => {
              if (await change(rate, 'custom')) setCustomOpen(false);
            }}
          />
        </PopoverContent>
      </Popover>
    </div>
  );

  return (
    <div className="flex flex-wrap items-center gap-2.5">
      <span aria-hidden="true" className="text-sm text-muted-foreground">
        {speed.label}
      </span>
      {locked ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <span tabIndex={0} className="rounded-md">
              {control}
            </span>
          </TooltipTrigger>
          <TooltipContent>{speed.loadRamp}</TooltipContent>
        </Tooltip>
      ) : (
        control
      )}
      <ConfirmDialog
        open={pending !== undefined}
        title={speed.pauseTitle(speed.streams[stream ?? 'both'])}
        description={speed.pauseBody}
        confirmLabel={speed.pauseConfirm}
        tone="danger"
        onConfirm={async () => {
          if (pending) await send(pending, 'segmented');
        }}
        onOpenChange={(open) => {
          if (!open) setPending(undefined);
        }}
      />
    </div>
  );
}

interface CustomRateProps {
  rate: SimStatus['rate'];
  errors: Partial<Record<RateField, string>>;
  onErrors: (errors: Partial<Record<RateField, string>>) => void;
  onApply: (rate: Required<Rate>) => Promise<void>;
}

/** "Custom rate" (§4): GTFS-rt and ticketing apart, each 0 or 0.1–20. */
function CustomRate({ rate, errors, onErrors, onApply }: CustomRateProps) {
  const [values, setValues] = useState<Record<RateField, string>>({
    gtfsRt: String(rate.gtfsRt),
    ticketing: String(rate.ticketing),
  });
  const [busy, setBusy] = useState(false);
  const parsed = {
    gtfsRt: values.gtfsRt.trim() === '' ? Number.NaN : Number(values.gtfsRt),
    ticketing: values.ticketing.trim() === '' ? Number.NaN : Number(values.ticketing),
  };
  const local: Partial<Record<RateField, string>> = {};
  for (const field of RATE_FIELDS) {
    if (values[field].trim() !== '' && !rateAllowed(parsed[field])) local[field] = speed.range;
  }
  const valid = RATE_FIELDS.every((field) => rateAllowed(parsed[field]));

  return (
    <form
      noValidate
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        if (!valid || busy) return;
        setBusy(true);
        void onApply(parsed).finally(() => {
          setBusy(false);
        });
      }}
    >
      <p className="text-sm font-semibold">{speed.customRate}</p>
      {RATE_FIELDS.map((field) => (
        <RateInput
          key={field}
          label={speed[field]}
          value={values[field]}
          error={local[field] ?? errors[field]}
          onChange={(value) => {
            setValues((current) => ({ ...current, [field]: value }));
            if (errors[field]) onErrors({ ...errors, [field]: undefined });
          }}
        />
      ))}
      <Button type="submit" size="sm" disabled={!valid || busy} aria-busy={busy} className="self-end">
        {speed.apply}
      </Button>
    </form>
  );
}

function RateInput({
  label,
  value,
  error,
  onChange,
}: {
  label: string;
  value: string;
  error: string | undefined;
  onChange: (value: string) => void;
}) {
  const id = useId();
  const errorId = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-label font-medium text-foreground-2">
        {label}
      </label>
      <Input
        id={id}
        type="number"
        inputMode="decimal"
        min={0}
        max={20}
        step={0.1}
        value={value}
        aria-invalid={error !== undefined}
        {...(error ? { 'aria-describedby': errorId } : {})}
        onChange={(event) => {
          onChange(event.target.value);
        }}
      />
      {error ? (
        <p id={errorId} className="text-xs text-tone-danger-fg">
          {error}
        </p>
      ) : null}
    </div>
  );
}
