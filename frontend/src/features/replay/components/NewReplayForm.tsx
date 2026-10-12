import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CircleHelp } from 'lucide-react';
import { useEffect, useId, useMemo, useState, type ReactNode } from 'react';

import { api, newIdempotencyKey, write } from '@/api/client';
import { isApiError } from '@/api/problem';
import { useFreshness } from '@/app/freshness';
import { AppLink } from '@/components/AppLink';
import { Callout } from '@/components/Callout';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { KeyValueList } from '@/components/KeyValueList';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { daySummaryQuery } from '@/features/ops-jobs/queries';
import {
  HREF,
  hourBars,
  offsetMs,
  prefill,
  presetRange,
  TOPICS,
  volumeBySource,
  type HourBar,
  type Preset,
  type ReplayEstimate,
} from '@/features/replay/model';
import { activeQuery, estimateQuery, type EstimateParams } from '@/features/replay/queries';
import { checkReplayForm, type FieldErrors, type ReplayField, type ReplayFormValues } from '@/features/replay/schema';
import { SOURCES, type ReplaySearch, type ReplaySource } from '@/features/replay/search';
import { en } from '@/i18n/en';
import { replayCopy } from '@/i18n/replay';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCompact, formatDuration, formatPercentWhole } from '@/lib/format';
import { notify } from '@/lib/notify';
import { describeError, type DescribedError } from '@/lib/problem-copy';
import { formatDateTime, formatTime, fromLocalInput, toLocalInput, zoneAbbreviation } from '@/lib/time';
import { cn } from '@/lib/utils';

const copy = replayCopy.form;
const MINUTE_MS = 60_000;
/** The estimate follows the form once typing stops (§4). */
const ESTIMATE_DEBOUNCE_MS = 500;
/** The real clock moves the "10 minutes ago" limit; the form looks again this often. */
const CLOCK_TICK_MS = 30_000;
/** E-53 says so when the source already has a replay waiting or running (DOC-32 E-53). */
const ALREADY_RUNNING = 'A replay for this source is already running.';
const PRESETS: Preset[] = ['lastHour', 'last24h', 'yesterday'];

function useWallClock(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now());
    }, CLOCK_TICK_MS);
    return () => {
      clearInterval(timer);
    };
  }, []);
  return now;
}

interface NewReplayFormProps {
  search: ReplaySearch;
  /** The replay was created (or already runs): show it in the history drawer. */
  onSent: (id: string) => void;
}

/** Tab "New replay" (§4, operator only): source, record time range, options, and the estimate with "Start replay". */
export function NewReplayForm({ search, onSent }: NewReplayFormProps) {
  const queryClient = useQueryClient();
  const clock = useBusinessClock();
  const freshness = useFreshness();
  const now = useWallClock();
  // The URL fills the form once, when it opens; edits stay in the form (§2).
  const [values, setValues] = useState<ReplayFormValues>(() => prefill(search));
  // Errors of the API, shown until the values they were about change (§6).
  const [server, setServer] = useState<{ values: ReplayFormValues; errors: FieldErrors } | undefined>(undefined);
  const [formError, setFormError] = useState<DescribedError | undefined>(undefined);
  const [confirming, setConfirming] = useState<string | undefined>(undefined);
  const [conflict, setConflict] = useState<string | undefined>(undefined);
  const recomputeLabelId = useId();
  const recomputeHelpId = useId();

  const check = checkReplayForm(values, now);
  const fieldErrors: FieldErrors = {
    ...(check.ok ? {} : check.errors),
    ...(server?.values === values ? server.errors : {}),
  };
  const body = check.ok ? check.body : undefined;

  // E-53 once the form has been valid and unchanged for 500 ms.
  const [debounced, setDebounced] = useState<EstimateParams | undefined>(undefined);
  const paramsKey = body ? `${body.source}|${body.fromTs}|${body.toTs}` : '';
  useEffect(() => {
    if (!paramsKey) return;
    const [source = '', fromTs = '', toTs = ''] = paramsKey.split('|');
    const timer = setTimeout(() => {
      setDebounced({ source, fromTs, toTs });
    }, ESTIMATE_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [paramsKey]);
  const current = debounced !== undefined && `${debounced.source}|${debounced.fromTs}|${debounced.toTs}` === paramsKey;
  const estimate = useQuery({
    ...estimateQuery(debounced ?? { source: '', fromTs: '', toTs: '' }),
    enabled: current,
  });
  const day = useQuery(daySummaryQuery());
  const active = useQuery(activeQuery());

  const estimateData = current ? estimate.data?.data : undefined;
  const alreadyRunning = estimateData?.warnings.includes(ALREADY_RUNNING) ?? false;
  const runningReplay = active.data?.data.items.find(
    (replay) => replay.kind === 'RAW_RANGE' && replay.source === values.source,
  );
  const canStart = body !== undefined && !alreadyRunning && confirming === undefined;

  const change = (patch: Partial<ReplayFormValues>) => {
    setValues((previous) => ({ ...previous, ...patch }));
    setFormError(undefined);
  };

  const submit = async (key: string) => {
    if (!body) return;
    try {
      const replay = await write(
        api.POST('/api/v1/etl/replays', { body, params: { header: { 'Idempotency-Key': key } } }),
      );
      void queryClient.invalidateQueries({ queryKey: ['etl', 'replays'] });
      notify.success(replayCopy.feedback.sent, {
        action: {
          label: replayCopy.feedback.view,
          onClick: () => {
            onSent(replay.id);
          },
        },
      });
      onSent(replay.id);
    } catch (error: unknown) {
      if (!isApiError(error)) throw error;
      const described = describeError(error);
      if (error.status === 409 && error.slug === 'replay-already-running') {
        setConflict(described.existingReplayId ?? runningReplay?.id ?? '');
        return;
      }
      if (error.status === 422 && error.slug === 'replay-window-invalid' && described.fieldErrors.length > 0) {
        const errors: FieldErrors = {};
        for (const item of described.fieldErrors) {
          const field: ReplayField = item.field === 'fromTs' ? 'fromTs' : item.field === 'source' ? 'source' : 'toTs';
          errors[field] ??= item.message;
        }
        setServer({ values, errors });
        return;
      }
      if (error.status === 422) {
        setFormError(described);
        return;
      }
      throw error;
    }
  };

  const sourceLabel = values.source ? en.source[values.source] : '';
  const rangeLabel = (from: string, to: string) => ({
    from: formatDateTime(from, { timeZone: clock.timezone, showZone: false }),
    to: formatDateTime(to, { timeZone: clock.timezone }),
  });

  return (
    <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
      <form
        aria-label={copy.label}
        className="flex min-w-0 flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
        }}
      >
        {formError ? (
          <div role="alert">
            <Callout tone="danger" title={formError.title}>
              {formError.description ?? formError.title}
            </Callout>
          </div>
        ) : null}
        <SourceStep
          value={values.source}
          volumes={volumeBySource(day.data?.data)}
          loading={day.isPending}
          error={fieldErrors.source}
          onChange={(source) => {
            change({ source });
          }}
        />
        <RangeStep
          values={values}
          now={now}
          errors={fieldErrors}
          bars={hourBars(
            day.data?.data,
            values.source,
            check.ok ? { from: Date.parse(check.body.fromTs), to: Date.parse(check.body.toTs) } : undefined,
          )}
          barsLoading={day.isPending}
          clockOffset={freshness.data?.clockOffset}
          onChange={change}
        />
        <Step number={3} title={copy.options}>
          <div className="flex items-start justify-between gap-4">
            <div>
              <p id={recomputeLabelId} className="text-base font-medium">
                {copy.recompute}
              </p>
              <p id={recomputeHelpId} className="text-sm text-muted-foreground">
                {copy.recomputeHelp}
              </p>
            </div>
            <Switch
              checked={values.recomputeAnalytics}
              aria-labelledby={recomputeLabelId}
              aria-describedby={recomputeHelpId}
              onCheckedChange={(recomputeAnalytics) => {
                change({ recomputeAnalytics });
              }}
            />
          </div>
        </Step>
      </form>

      <EstimateCard
        ready={body !== undefined}
        loading={current ? estimate.isPending : body !== undefined}
        failed={current && estimate.isError}
        estimate={estimateData}
        sourceLabel={sourceLabel}
        rangeMs={body ? Date.parse(body.toTs) - Date.parse(body.fromTs) : undefined}
        recompute={values.recomputeAnalytics}
        runningId={alreadyRunning ? runningReplay?.id : undefined}
        onViewRunning={onSent}
        start={
          <Button
            className="w-full"
            disabled={!canStart}
            onClick={() => {
              setConfirming(newIdempotencyKey());
            }}
          >
            {copy.start}
          </Button>
        }
      />

      {body ? (
        <ConfirmDialog
          open={confirming !== undefined}
          title={replayCopy.dialog.title(
            sourceLabel,
            rangeLabel(body.fromTs, body.toTs).from,
            rangeLabel(body.fromTs, body.toTs).to,
          )}
          description={
            <>
              <p>
                {estimateData?.estimatedMessages !== undefined
                  ? replayCopy.dialog.body(formatCompact(estimateData.estimatedMessages))
                  : replayCopy.dialog.bodyUnknown}
              </p>
              {values.recomputeAnalytics ? <p className="mt-1">{replayCopy.dialog.recompute}</p> : null}
            </>
          }
          confirmLabel={replayCopy.dialog.confirm}
          onConfirm={() => submit(confirming ?? newIdempotencyKey())}
          onOpenChange={(open) => {
            if (!open) setConfirming(undefined);
          }}
        />
      ) : null}
      <ConflictDialog
        replayId={conflict}
        onView={(id) => {
          setConflict(undefined);
          onSent(id);
        }}
        onClose={() => {
          setConflict(undefined);
        }}
      />
    </div>
  );
}

function Step({ number, title, children }: { number: number; title: string; children: ReactNode }) {
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      className="rounded-lg border border-border bg-card px-4 pt-3.5 pb-4 text-card-foreground shadow-sm"
    >
      <h2 id={titleId} className="mb-3 flex items-center gap-2 text-base font-semibold tracking-title">
        <span
          aria-hidden="true"
          className="inline-flex size-5 items-center justify-center rounded-full bg-foreground text-xs text-card"
        >
          {number}
        </span>
        {title}
      </h2>
      {children}
    </section>
  );
}

function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (!message) return null;
  return (
    <p id={id} className="text-xs text-tone-danger-fg">
      {message}
    </p>
  );
}

interface SourceStepProps {
  value: ReplaySource | undefined;
  volumes: Partial<Record<string, number>>;
  loading: boolean;
  error: string | undefined;
  onChange: (source: ReplaySource) => void;
}

/** ① The four sources as radio cards, each with its topic and the records read in 24 hours (E-31). */
function SourceStep({ value, volumes, loading, error, onChange }: SourceStepProps) {
  const errorId = useId();
  return (
    <Step number={1} title={copy.what}>
      <RadioGroup
        aria-label={copy.what}
        value={value ?? ''}
        onValueChange={(next) => {
          onChange(next as ReplaySource);
        }}
        aria-describedby={error ? errorId : undefined}
        className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4"
      >
        {SOURCES.map((source) => {
          const volume = volumes[source];
          return (
            <RadioGroupItem
              key={source}
              value={source}
              aria-label={en.source[source]}
              className={cn(
                'flex min-w-0 flex-col items-start gap-0.5 rounded-md border px-3 py-2.5 text-left motion-safe:transition-colors',
                'border-border bg-card hover:bg-muted data-[state=checked]:border-primary data-[state=checked]:bg-tone-info-bg',
              )}
            >
              <span className="flex w-full items-center justify-between gap-2 text-base font-medium">
                {en.source[source]}
                <span
                  aria-hidden="true"
                  className={cn(
                    'size-3 shrink-0 rounded-full border',
                    value === source ? 'border-4 border-primary' : 'border-border-strong',
                  )}
                />
              </span>
              <span className="w-full truncate font-mono text-xs text-muted-foreground">{TOPICS[source]}</span>
              <span className="text-xs text-muted-foreground tabular-nums">
                {volume !== undefined ? copy.perDay(formatCompact(volume)) : loading ? '…' : en.kv.empty}
              </span>
            </RadioGroupItem>
          );
        })}
      </RadioGroup>
      <FieldError id={errorId} message={error} />
      <p className="mt-2 text-sm text-muted-foreground">
        {copy.gtfsBefore}
        <AppLink href={HREF.runJob} className="text-primary hover:underline">
          {copy.gtfsLink}
        </AppLink>
        {copy.gtfsAfter}
      </p>
    </Step>
  );
}

interface RangeStepProps {
  values: ReplayFormValues;
  now: number;
  errors: FieldErrors;
  bars: HourBar[];
  barsLoading: boolean;
  clockOffset: string | undefined;
  onChange: (patch: Partial<ReplayFormValues>) => void;
}

/** ② Presets, From and To in the agency's zone, the hourly bars and the matching business time. */
function RangeStep({ values, now, errors, bars, barsLoading, clockOffset, onChange }: RangeStepProps) {
  const clock = useBusinessClock();
  const timeZone = clock.timezone;
  const fromId = useId();
  const toId = useId();
  const fromErrorId = useId();
  const toErrorId = useId();
  const offset = offsetMs(clockOffset);
  const from = Date.parse(values.fromTs);
  const to = Date.parse(values.toTs);
  const toInstant = (local: string) => {
    const ms = fromLocalInput(local, timeZone);
    return Number.isNaN(ms) ? '' : new Date(ms).toISOString();
  };

  return (
    <Step number={2} title={copy.range}>
      <div role="group" aria-label={copy.presetsLabel} className="mb-3 flex flex-wrap gap-2">
        {PRESETS.map((preset) => (
          <Button
            key={preset}
            variant="outline"
            size="sm"
            onClick={() => {
              onChange(presetRange(preset, now, timeZone));
            }}
          >
            {copy.presets[preset]}
          </Button>
        ))}
      </div>
      <div className="flex flex-wrap items-start gap-3">
        <div className="flex min-w-52 flex-col gap-1">
          <label htmlFor={fromId} className="text-label font-medium text-foreground-2">
            {copy.from}
          </label>
          <Input
            id={fromId}
            type="datetime-local"
            value={values.fromTs ? toLocalInput(values.fromTs, timeZone) : ''}
            aria-invalid={errors.fromTs !== undefined}
            aria-describedby={errors.fromTs ? fromErrorId : undefined}
            onChange={(event) => {
              onChange({ fromTs: toInstant(event.target.value) });
            }}
          />
          <FieldError id={fromErrorId} message={errors.fromTs} />
        </div>
        <span aria-hidden="true" className="mt-7 text-muted-foreground">
          →
        </span>
        <div className="flex min-w-52 flex-col gap-1">
          <label htmlFor={toId} className="text-label font-medium text-foreground-2">
            {copy.to}
          </label>
          <div className="flex items-center gap-2">
            <Input
              id={toId}
              type="datetime-local"
              value={values.toTs ? toLocalInput(values.toTs, timeZone) : ''}
              aria-invalid={errors.toTs !== undefined}
              aria-describedby={errors.toTs ? toErrorId : undefined}
              onChange={(event) => {
                onChange({ toTs: toInstant(event.target.value) });
              }}
            />
            <span className="text-sm text-muted-foreground">{zoneAbbreviation(now, timeZone)}</span>
          </div>
          <FieldError id={toErrorId} message={errors.toTs} />
        </div>
      </div>

      <HourlyBars bars={bars} loading={barsLoading} />

      {offset !== 0 && !Number.isNaN(from) && !Number.isNaN(to) ? (
        <p className="mt-2 flex items-center gap-1.5 text-sm text-muted-foreground">
          {copy.businessTime(
            formatDateTime(from + offset, { timeZone, showZone: false }),
            formatDateTime(to + offset, { timeZone }),
            `${offset > 0 ? '+' : '−'}${formatDuration(Math.abs(offset))}`,
          )}
          <Help text={replayCopy.help.recordTime} />
        </p>
      ) : null}
    </Step>
  );
}

function Help({ text }: { text: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button type="button" aria-label={replayCopy.help.label} className="rounded-sm text-muted-foreground">
          <CircleHelp className="size-3.5" aria-hidden="true" />
        </button>
      </TooltipTrigger>
      <TooltipContent className="max-w-72">{text}</TooltipContent>
    </Tooltip>
  );
}

/** "Processed per hour · last 24 h": one bar per hour of the source; hours in the chosen range are filled. */
function HourlyBars({ bars, loading }: { bars: HourBar[]; loading: boolean }) {
  const clock = useBusinessClock();
  const labelId = useId();
  const max = useMemo(() => Math.max(1, ...bars.map((bar) => bar.read)), [bars]);
  return (
    <div className="mt-4">
      <p id={labelId} className="mb-1.5 text-xs font-medium text-muted-foreground">
        {copy.perHour}
      </p>
      {loading ? (
        <Skeleton className="h-10 w-full" />
      ) : bars.length === 0 ? (
        <p className="text-xs text-muted-foreground">{en.chart.noData}</p>
      ) : (
        <ul aria-labelledby={labelId} className="flex h-10 items-end gap-0.5">
          {bars.map((bar) => (
            <li
              key={bar.start}
              title={formatTime(bar.start, { timeZone: clock.timezone })}
              className={cn('min-h-px flex-1 rounded-t-[2px]', bar.selected ? 'bg-primary' : 'bg-muted-2')}
              style={{ height: `${Math.max(4, (bar.read / max) * 100)}%` }}
            >
              <span className="sr-only">
                {copy.perHourBar(
                  formatTime(bar.start, { timeZone: clock.timezone, showZone: false }),
                  formatCompact(bar.read),
                  bar.selected,
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface EstimateCardProps {
  /** The form is valid: an estimate is (or will be) asked for. */
  ready: boolean;
  loading: boolean;
  failed: boolean;
  estimate: ReplayEstimate | undefined;
  sourceLabel: string;
  rangeMs: number | undefined;
  recompute: boolean;
  /** The replay already running for the source, when E-53 says one is. */
  runningId: string | undefined;
  onViewRunning: (id: string) => void;
  start: ReactNode;
}

/** "Estimated work": sticky beside the form, with the warnings of the API verbatim and "Start replay" (§4, §6). */
function EstimateCard({
  ready,
  loading,
  failed,
  estimate,
  sourceLabel,
  rangeMs,
  recompute,
  runningId,
  onViewRunning,
  start,
}: EstimateCardProps) {
  const e = replayCopy.estimate;
  const unavailable = estimate?.basis === 'UNAVAILABLE' || (estimate && estimate.estimatedMessages === undefined);
  const durationSeconds = estimate?.estimatedDurationSeconds;
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      aria-busy={ready && loading}
      className="flex flex-col gap-3 rounded-lg border border-border bg-card px-4 py-3.5 shadow-sm lg:sticky lg:top-4"
    >
      <h2 id={titleId} className="flex items-center gap-1.5 text-base font-semibold tracking-title">
        {e.title}
        <Help text={replayCopy.help.estimate} />
      </h2>
      {!ready ? (
        <p className="text-sm text-muted-foreground">{e.pending}</p>
      ) : loading ? (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-7 w-32" />
          <Skeleton className="h-4 w-48" />
        </div>
      ) : failed ? (
        <p className="text-sm text-muted-foreground">{e.failed}</p>
      ) : estimate ? (
        <>
          {unavailable ? (
            <p className="text-base font-medium">{e.unavailable}</p>
          ) : (
            <p className="flex items-baseline gap-1.5">
              <span className="text-page font-semibold tabular-nums">
                {e.messages(formatCompact(estimate.estimatedMessages ?? 0))}
              </span>
              <span className="text-sm text-muted-foreground">{e.messagesUnit}</span>
            </p>
          )}
          <KeyValueList
            items={[
              { label: e.source, value: sourceLabel },
              { label: e.range, value: rangeMs === undefined ? en.kv.empty : formatDuration(rangeMs) },
              ...(durationSeconds !== undefined && !unavailable
                ? [{ label: e.duration, value: e.about(formatDuration(roundedDuration(durationSeconds))) }]
                : []),
              { label: e.coverage, value: formatPercentWhole(estimate.coverage) },
              { label: e.recompute, value: recompute ? replayCopy.yes : replayCopy.no },
            ]}
          />
        </>
      ) : null}
      {estimate?.warnings.map((warning) => (
        <Callout key={warning} tone="warning">
          <p>{warning}</p>
          {warning === ALREADY_RUNNING && runningId ? (
            <button
              type="button"
              className="mt-1 font-medium underline underline-offset-2"
              onClick={() => {
                onViewRunning(runningId);
              }}
            >
              {e.viewRunning}
            </button>
          ) : null}
        </Callout>
      ))}
      {start}
    </section>
  );
}

/** Whole minutes from a minute on ("about 6 min"); seconds below. */
function roundedDuration(seconds: number): number {
  return seconds >= 60 ? Math.round(seconds / 60) * MINUTE_MS : seconds * 1000;
}

interface ConflictDialogProps {
  replayId: string | undefined;
  onView: (id: string) => void;
  onClose: () => void;
}

/** 409 `replay-already-running` (FR-12.3, AC-4): the confirmation turns into a link to the running replay. */
function ConflictDialog({ replayId, onView, onClose }: ConflictDialogProps) {
  const d = replayCopy.dialog;
  return (
    <Dialog
      open={replayId !== undefined}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent>
        <div className="flex flex-col gap-3 p-5">
          <DialogTitle className="text-panel font-semibold tracking-title">{d.conflictTitle}</DialogTitle>
          <DialogDescription className="text-base text-muted-foreground">{d.conflictBody}</DialogDescription>
        </div>
        <div className="flex items-center justify-end gap-2 rounded-b-dialog border-t border-border bg-surface px-5 py-3">
          <Button variant="outline" onClick={onClose}>
            {en.common.close}
          </Button>
          {replayId ? (
            <Button
              onClick={() => {
                onView(replayId);
              }}
            >
              {d.viewRunning}
            </Button>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
