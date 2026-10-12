import { useId, useState } from 'react';

import type { components } from '@/api/generated/schema';
import { Callout } from '@/components/Callout';
import { Card } from '@/components/Card';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { ToneBadge } from '@/components/ToneBadge';
import { FlagRowFrame, FlagSwitch } from '@/features/controls/components/parts';
import {
  CONSUMERS,
  FEATURES,
  flagOf,
  lastProcessed,
  longFor,
  type Consumer,
  type FeatureKey,
  type JobSummary,
  type RuntimeFlag,
} from '@/features/controls/model';
import type { FlagToggle } from '@/features/controls/use-flag-toggle';
import { controlsCopy } from '@/i18n/controls';
import { en } from '@/i18n/en';
import { actorName, formatDuration } from '@/lib/format';
import { useAxisNow, useRelative } from '@/lib/use-now';
import { cn } from '@/lib/utils';

type Source = components['schemas']['SourceResponse'];

const copy = controlsCopy;
const sourceLabels: Partial<Record<string, string>> = en.source;

interface FlagsState {
  flags: readonly RuntimeFlag[] | undefined;
  error: unknown;
  /** The last good answer is shown under an inline error. */
  dataAsOf?: string;
  onRetry: () => void;
}

/** E-55 failed: the card says so and shows no switch with a guessed value (§7). */
function FlagsUnavailable({ state, rows, panel }: { state: FlagsState; rows: number; panel: string }) {
  if (state.flags) {
    return state.error ? (
      <ErrorState
        error={state.error}
        variant="inline"
        onRetry={state.onRetry}
        axis="audit"
        {...(state.dataAsOf ? { dataAsOf: state.dataAsOf } : {})}
      />
    ) : null;
  }
  if (state.error) {
    return <ErrorState error={state.error} variant="block" panel={panel} onRetry={state.onRetry} />;
  }
  return <PanelSkeleton variant="list" rows={rows} />;
}

// ---------------------------------------------------------------------------------------------------------------------
// Pipeline

interface PipelineCardProps {
  state: FlagsState;
  summary: JobSummary | undefined;
  sources: readonly Source[] | undefined;
  operator: boolean;
  toggle: FlagToggle;
}

/** The two consumers: a switch that reads "running", the listeners' activity and who paused them (§4, §6). */
export function PipelineCard({ state, summary, sources, operator, toggle }: PipelineCardProps) {
  const [asking, setAsking] = useState<Consumer | undefined>(undefined);
  const rows = state.flags
    ? CONSUMERS.flatMap((consumer) => {
        const flag = flagOf(state.flags ?? [], consumer.key);
        return flag ? [{ consumer, flag }] : [];
      })
    : [];

  return (
    <Card title={copy.sections.pipeline}>
      <p className="mb-2 text-sm text-muted-foreground">{copy.intro.pipeline}</p>
      <FlagsUnavailable state={state} rows={2} panel={copy.sections.pipeline} />
      {rows.map(({ consumer, flag }) => (
        <ConsumerRow
          key={consumer.key}
          consumer={consumer}
          flag={flag}
          summary={summary}
          sources={sources}
          operator={operator}
          flash={toggle.failed === consumer.key}
          onRunning={(running) => {
            // Pausing asks first; resuming restores the normal state and does not (§6).
            if (running) toggle.set(consumer.key, false, copy.feedback.resumed);
            else setAsking(consumer);
          }}
        />
      ))}
      <ConfirmDialog
        open={asking !== undefined}
        title={copy.confirm.pauseTitle(asking ? copy.consumer[asking.id].group : '')}
        description={copy.confirm.pauseBody}
        confirmLabel={copy.confirm.pause}
        onConfirm={() => {
          if (asking) toggle.set(asking.key, true, copy.feedback.paused);
          return Promise.resolve();
        }}
        onOpenChange={(open) => {
          if (!open) setAsking(undefined);
        }}
      />
    </Card>
  );
}

interface ConsumerRowProps {
  consumer: Consumer;
  flag: RuntimeFlag;
  summary: JobSummary | undefined;
  sources: readonly Source[] | undefined;
  operator: boolean;
  flash: boolean;
  onRunning: (running: boolean) => void;
}

function ConsumerRow({ consumer, flag, summary, sources, operator, flash, onRunning }: ConsumerRowProps) {
  const descriptionId = useId();
  const paused = flag.value === true;
  const text = copy.consumer[consumer.id];
  const now = useAxisNow(flag.updatedAt, 'audit');
  const long = paused ? longFor(flag, now) : undefined;

  return (
    <FlagRowFrame flash={flash}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="font-medium">{text.name}</p>
          <p id={descriptionId} className="text-sm text-muted-foreground">
            {text.description}
          </p>
        </div>
        {long !== undefined ? <ToneBadge tone="warning" label={copy.consumer.pausedFor(formatDuration(long))} /> : null}
        <span className={cn('text-sm font-medium', paused ? 'text-tone-warning-fg' : 'text-muted-foreground')}>
          {paused ? copy.consumer.paused : copy.consumer.running}
        </span>
        <FlagSwitch
          checked={!paused}
          operator={operator}
          label={text.name}
          describedBy={descriptionId}
          onChange={onRunning}
        />
      </div>
      <ul className="flex flex-col gap-0.5">
        {consumer.sources.map((source) => (
          <ListenerLine
            key={source}
            source={source}
            lastAt={lastProcessed(summary, source)}
            known={summary !== undefined}
            freshness={sources?.find((item) => item.source === source)}
            running={!paused}
          />
        ))}
      </ul>
      {paused ? <PausedCallout flag={flag} gtfsRt={consumer.id === 'gtfsRt'} /> : null}
    </FlagRowFrame>
  );
}

function PausedCallout({ flag, gtfsRt }: { flag: RuntimeFlag; gtfsRt: boolean }) {
  const relative = useRelative(flag.updatedAt, 'audit');
  return (
    <Callout tone="warning">
      {copy.consumer.pausedBy(actorName(flag.updatedBy), relative)}
      {gtfsRt ? ` ${copy.consumer.staleSoon}` : null}
    </Callout>
  );
}

interface ListenerLineProps {
  source: string;
  lastAt: string | undefined;
  /** E-31 has answered, so no time means no batch in 15 minutes rather than not known yet. */
  known: boolean;
  freshness: Source | undefined;
  running: boolean;
}

/** "{source} · last processed {relative}", and the newest event of the source on the event axis (§4). */
function ListenerLine({ source, lastAt, known, freshness, running }: ListenerLineProps) {
  const label = sourceLabels[source] ?? source;
  return (
    <li className="text-xs text-muted-foreground">
      {lastAt ? (
        <Relative at={lastAt} axis="audit" render={(relative) => copy.listener.lastProcessed(label, relative)} />
      ) : known ? (
        <span className={cn(running && 'text-tone-warning-fg')}>{copy.listener.noBatches(label)}</span>
      ) : (
        label
      )}
      {freshness?.lastEventAt ? (
        <>
          {' · '}
          <Relative
            at={freshness.lastEventAt}
            axis="event"
            render={(relative) => copy.listener.latestEvent(relative)}
          />
        </>
      ) : null}
      {freshness?.stale ? ` ${copy.listener.sourceQuiet}` : null}
    </li>
  );
}

function Relative({ at, axis, render }: { at: string; axis: 'audit' | 'event'; render: (relative: string) => string }) {
  const relative = useRelative(at, axis);
  return <time dateTime={at}>{render(relative)}</time>;
}

// ---------------------------------------------------------------------------------------------------------------------
// AI features

interface AiCardProps {
  state: FlagsState;
  operator: boolean;
  toggle: FlagToggle;
}

/** The `triage.*` flags (§4): turning one off asks first, turning one on does not (§6). */
export function AiFeaturesCard({ state, operator, toggle }: AiCardProps) {
  const [asking, setAsking] = useState<FeatureKey | undefined>(undefined);
  const rows = state.flags
    ? FEATURES.flatMap((key) => {
        const flag = flagOf(state.flags ?? [], key);
        return flag ? [{ key, flag }] : [];
      })
    : [];

  return (
    <Card title={copy.sections.ai}>
      <p className="mb-2 text-sm text-muted-foreground">{copy.intro.ai}</p>
      <FlagsUnavailable state={state} rows={5} panel={copy.sections.ai} />
      {rows.map(({ key, flag }) => (
        <FeatureRow
          key={key}
          featureKey={key}
          flag={flag}
          operator={operator}
          flash={toggle.failed === key}
          onChange={(on) => {
            if (on) toggle.set(key, true, copy.feedback.turnedOn(copy.feature[key].label));
            else setAsking(key);
          }}
        />
      ))}
      <ConfirmDialog
        open={asking !== undefined}
        title={copy.confirm.turnOffTitle(asking ? copy.feature[asking].label : '')}
        description={asking ? copy.feature[asking].offBody : ''}
        confirmLabel={copy.confirm.turnOff}
        tone="danger"
        onConfirm={() => {
          if (asking) toggle.set(asking, false, copy.feedback.turnedOff(copy.feature[asking].label));
          return Promise.resolve();
        }}
        onOpenChange={(open) => {
          if (!open) setAsking(undefined);
        }}
      />
    </Card>
  );
}

interface FeatureRowProps {
  featureKey: FeatureKey;
  flag: RuntimeFlag;
  operator: boolean;
  flash: boolean;
  onChange: (on: boolean) => void;
}

function FeatureRow({ featureKey, flag, operator, flash, onChange }: FeatureRowProps) {
  const descriptionId = useId();
  const on = flag.value === true;
  const text = copy.feature[featureKey];
  const now = useAxisNow(flag.updatedAt, 'audit');
  const relative = useRelative(flag.updatedAt, 'audit');
  const long = on ? undefined : longFor(flag, now);
  return (
    <FlagRowFrame flash={flash}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="font-medium">{text.label}</p>
          <p id={descriptionId} className="text-sm text-muted-foreground">
            {text.description}
          </p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {copy.flag.changedBy(actorName(flag.updatedBy), relative)}
          </p>
        </div>
        {long !== undefined ? <ToneBadge tone="warning" label={copy.flag.offFor(formatDuration(long))} /> : null}
        <FlagSwitch
          checked={on}
          operator={operator}
          label={text.label}
          describedBy={descriptionId}
          onChange={onChange}
        />
      </div>
    </FlagRowFrame>
  );
}
