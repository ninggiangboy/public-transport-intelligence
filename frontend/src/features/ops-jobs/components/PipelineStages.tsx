import { ArrowDown } from 'lucide-react';
import type { ReactNode } from 'react';

import { AppLink } from '@/components/AppLink';
import { Sparkline } from '@/components/Sparkline';
import { toneClasses, type Tone } from '@/components/tone';
import { Skeleton } from '@/components/ui/skeleton';
import { formatRate, HREF, type StreamStage } from '@/features/ops-jobs/model';
import { pipelineCopy } from '@/i18n/pipeline';
import { formatCount, formatDuration, formatPercent } from '@/lib/format';
import { cn } from '@/lib/utils';

const copy = pipelineCopy.pipeline.stages;

export interface StageTones {
  sources: Tone;
  stream: Tone;
  deadLetters: Tone;
  batchJobs: Tone;
}

interface PipelineStagesProps {
  /** E-31 by minute over 15 minutes; undefined while it loads. */
  stream?: StreamStage;
  feeds?: { total: number; stale: number };
  paused: boolean;
  deadLetters?: { open: number; createdLastHour: number };
  batchJobs?: { running: number; failed: number };
  tones: StageTones;
  onSources: () => void;
  onStream: () => void;
  onBatchJobs: () => void;
}

const BORDER: Record<Tone, string> = {
  neutral: 'border-border',
  info: 'border-tone-info-border',
  success: 'border-tone-success-border',
  teal: 'border-tone-teal-border',
  warning: 'border-tone-warning-border',
  danger: 'border-tone-danger-border',
  progress: 'border-tone-progress-border',
};

interface StageProps {
  title: string;
  subtitle?: string;
  tone: Tone;
  value?: ReactNode;
  detail?: ReactNode;
  children?: ReactNode;
  /** A button or a link; neither makes a plain card (Warehouse). */
  onClick?: () => void;
  href?: string;
}

function Stage({ title, subtitle, tone, value, detail, children, onClick, href }: StageProps) {
  const body = (
    <>
      <span className="flex items-start gap-2">
        <span className="min-w-0 flex-1">
          <span className="block font-mono text-sm font-semibold">{title}</span>
          {subtitle ? <span className="block text-xs text-muted-foreground">{subtitle}</span> : null}
        </span>
        <span
          className={cn(
            'mt-1.5 size-2 shrink-0 rounded-full',
            toneClasses(tone === 'neutral' ? 'success' : tone).solid,
          )}
          aria-hidden="true"
        />
      </span>
      <span className="mt-2 block text-kpi font-semibold tracking-kpi tabular-nums">
        {value ?? <Skeleton className="h-7 w-20" />}
      </span>
      <span className={cn('mt-1 block text-xs', tone === 'neutral' ? 'text-muted-foreground' : toneClasses(tone).text)}>
        {detail}
      </span>
      {children}
    </>
  );
  const className = cn(
    'block h-full w-full min-w-0 rounded-lg border bg-card p-3.5 text-left text-card-foreground shadow-xs',
    BORDER[tone],
    (onClick ?? href) && 'hover:border-border-strong hover:bg-surface focus-visible:bg-surface',
  );
  if (href) {
    return (
      <AppLink href={href} className={className} aria-label={copy.goTo(title)}>
        {body}
      </AppLink>
    );
  }
  if (onClick) {
    return (
      <button type="button" onClick={onClick} className={className} aria-label={copy.goTo(title)}>
        {body}
      </button>
    );
  }
  return <div className={className}>{body}</div>;
}

/** A dashed connector whose dashes run while its rate is above zero (DOC-35 §4.4). */
function Flow({ label, active, vertical = false }: { label: string; active: boolean; vertical?: boolean }) {
  const line = (
    <span
      aria-hidden="true"
      className={cn(
        'block',
        vertical ? 'h-5 w-0.5' : 'h-0.5 w-full min-w-8',
        active && !vertical && 'motion-safe:animate-flow',
      )}
      style={{
        backgroundImage: `linear-gradient(${vertical ? 'to bottom' : 'to right'}, var(${active ? '--muted-foreground' : '--border-strong'}) 50%, transparent 50%)`,
        backgroundSize: vertical ? '2px 8px' : '8px 2px',
      }}
    />
  );
  return vertical ? (
    <span className="flex items-center gap-2 py-1 pl-6 text-xs text-muted-foreground tabular-nums">
      {line}
      <ArrowDown className="-ml-3.5 size-3" aria-hidden="true" />
      <span>{label}</span>
    </span>
  ) : (
    <span className="flex flex-col items-center justify-center gap-1 px-1 text-xs text-muted-foreground tabular-nums">
      <span className="whitespace-nowrap">{label}</span>
      {line}
    </span>
  );
}

/**
 * Sources → etl-stream → Warehouse, with Dead letters under etl-stream and Batch jobs under Warehouse (§4.1). Kafka is
 * not in the product API; the Grafana button covers it.
 */
export function PipelineStages({
  stream,
  feeds,
  paused,
  deadLetters,
  batchJobs,
  tones,
  onSources,
  onStream,
  onBatchJobs,
}: PipelineStagesProps) {
  const read = stream ? formatRate(stream.readPerSecond) : undefined;
  const written = stream ? formatRate(stream.writtenPerSecond) : undefined;
  return (
    <section aria-label={copy.label} className="rounded-lg border border-border bg-card p-4 shadow-sm">
      <div className="grid grid-cols-[minmax(0,1fr)_4.5rem_minmax(0,1fr)_4.5rem_minmax(0,1fr)] items-stretch gap-y-1">
        <Stage
          title={copy.sources}
          subtitle={copy.sourcesSub}
          tone={tones.sources}
          value={read === undefined ? undefined : <Unit value={read} unit={copy.unit.msgPerSecond} />}
          detail={feeds ? copy.feeds(feeds.total, feeds.stale) : undefined}
          onClick={onSources}
        />
        <Flow label={copy.rate(read ?? '—')} active={(stream?.readPerSecond ?? 0) > 0} />
        <Stage
          title={copy.stream}
          tone={tones.stream}
          value={
            stream ? <Unit value={formatRate(stream.batchesPerMinute)} unit={copy.unit.batchesPerMinute} /> : undefined
          }
          detail={
            stream
              ? paused
                ? copy.paused
                : copy.streamSub(stream.p95Ms === undefined ? '—' : formatDuration(stream.p95Ms), stream.failedRecently)
              : undefined
          }
          onClick={onStream}
        />
        <Flow label={copy.rate(written ?? '—')} active={(stream?.writtenPerSecond ?? 0) > 0} />
        <Stage
          title={copy.warehouse}
          tone="neutral"
          value={written === undefined ? undefined : <Unit value={written} unit={copy.unit.writtenPerSecond} />}
          detail={stream?.skippedRatio === undefined ? undefined : copy.skipped(formatPercent(stream.skippedRatio))}
        >
          {stream && stream.writtenTrend.length > 1 ? (
            <span className="mt-2 block">
              <Sparkline points={stream.writtenTrend} label={copy.writtenTrend} />
            </span>
          ) : null}
        </Stage>

        <span className="col-start-3">
          <Flow
            vertical
            label={copy.hourly(deadLetters ? formatCount(deadLetters.createdLastHour) : '—')}
            active={(deadLetters?.createdLastHour ?? 0) > 0}
          />
        </span>
        <span className="col-start-5" />
        <span className="col-start-3">
          <Stage
            title={copy.deadLetters}
            tone={tones.deadLetters}
            value={deadLetters ? <Unit value={formatCount(deadLetters.open)} unit={copy.unit.open} /> : undefined}
            detail={deadLetters ? copy.perHour(formatCount(deadLetters.createdLastHour)) : undefined}
            href={HREF.deadLetters}
          />
        </span>
        <span className="col-start-5">
          <Stage
            title={copy.batchJobs}
            tone={tones.batchJobs}
            value={batchJobs ? <Unit value={formatCount(batchJobs.running)} unit={copy.unit.running} /> : undefined}
            detail={batchJobs ? copy.failed(formatCount(batchJobs.failed)) : undefined}
            onClick={onBatchJobs}
          />
        </span>
      </div>
    </section>
  );
}

function Unit({ value, unit }: { value: string; unit: string }) {
  return (
    <>
      {value}
      <span className="ml-1 text-sm font-medium tracking-normal text-muted-foreground">{unit}</span>
    </>
  );
}
