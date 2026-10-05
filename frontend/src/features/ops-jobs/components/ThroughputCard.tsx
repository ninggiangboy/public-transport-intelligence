import { useCallback, useMemo, useState } from 'react';

import { Card } from '@/components/Card';
import { TimeSeriesChart, type TimeSeries } from '@/components/charts/TimeSeriesChart';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Button } from '@/components/ui/button';
import {
  METRICS,
  noActivity,
  sourceSeries,
  totalsByBucket,
  totalSeries,
  type JobSummary,
  type Metric,
} from '@/features/ops-jobs/model';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCompact, formatCount, formatDuration } from '@/lib/format';
import { formatDateTime, formatTime } from '@/lib/time';

const copy = pipelineCopy.pipeline.throughput;
type View = 'all' | 'bySource';

const SOURCE_COLORS = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)'];
const METRIC_LABEL: Record<Metric, string> = { read: copy.read, written: copy.written, skipped: copy.skipped };

interface ThroughputCardProps {
  summary?: JobSummary;
  error?: unknown;
  onRetry: () => void;
  /** "Last hour", or the fixed range. */
  periodLabel: string;
  /** A fixed range picked by dragging; set when the URL has one, to offer "Reset zoom". */
  zoomed: boolean;
  onZoom: (from: number, to: number) => void;
  onResetZoom: () => void;
}

/** Read and written per bucket over every source, or one metric stacked by source (§4). Empty buckets are gaps. */
export function ThroughputCard({
  summary,
  error,
  onRetry,
  periodLabel,
  zoomed,
  onZoom,
  onResetZoom,
}: ThroughputCardProps) {
  const clock = useBusinessClock();
  const timeZone = clock.timezone;
  const [view, setView] = useState<View>('all');
  const [metric, setMetric] = useState<Metric>('read');

  const totals = useMemo(() => (summary ? totalsByBucket(summary) : undefined), [summary]);
  const series = useMemo<TimeSeries[]>(() => {
    if (!summary || !totals) return [];
    if (view === 'all') {
      return [
        { name: copy.read, color: 'var(--chart-1)', points: totalSeries(totals, 'read') },
        { name: copy.written, color: 'var(--chart-2)', points: totalSeries(totals, 'written') },
      ];
    }
    return sourceSeries(summary, metric).map((s, index) => ({
      name: (en.source as Record<string, string>)[s.source] ?? s.source,
      color: SOURCE_COLORS[index % SOURCE_COLORS.length],
      points: s.points,
    }));
  }, [summary, totals, view, metric]);

  const formatTick = useCallback((t: string) => formatTime(t, { timeZone, showZone: false }), [timeZone]);
  const tooltipExtra = useCallback(
    (t: string) => {
      const total = totals?.get(t);
      if (!total) return [];
      return [
        { label: copy.batches, value: formatCount(total.batches) },
        { label: copy.failedBatches, value: formatCount(total.failedBatches) },
        ...(view === 'bySource'
          ? [
              { label: copy.read, value: formatCount(total.read) },
              { label: copy.written, value: formatCount(total.written) },
            ]
          : []),
        { label: copy.skipped, value: formatCount(total.skipped) },
        { label: copy.duplicates, value: formatCount(total.duplicate) },
        { label: copy.p95, value: total.p95Ms === undefined ? en.kv.empty : formatDuration(total.p95Ms) },
      ];
    },
    [totals, view],
  );

  const caption = summary
    ? view === 'all'
      ? copy.caption(
          formatDateTime(summary.from, { timeZone, showZone: false }),
          formatDateTime(summary.to, { timeZone }),
          formatCount([...(totals?.values() ?? [])].reduce((sum, t) => sum + t.read, 0)),
          formatCount([...(totals?.values() ?? [])].reduce((sum, t) => sum + t.written, 0)),
        )
      : copy.captionBySource(
          METRIC_LABEL[metric],
          formatDateTime(summary.from, { timeZone, showZone: false }),
          formatDateTime(summary.to, { timeZone }),
        )
    : '';

  return (
    <Card
      title={copy.title}
      meta={periodLabel}
      actions={
        <>
          {zoomed ? (
            <Button variant="ghost" size="sm" onClick={onResetZoom}>
              {copy.resetZoom}
            </Button>
          ) : null}
          {view === 'bySource' ? (
            <SegmentedControl<Metric>
              label={copy.metric}
              size="sm"
              value={metric}
              options={METRICS.map((value) => ({ value, label: METRIC_LABEL[value] }))}
              onChange={setMetric}
            />
          ) : null}
          <SegmentedControl<View>
            label={copy.view}
            size="sm"
            value={view}
            options={[
              { value: 'all', label: copy.all },
              { value: 'bySource', label: copy.bySource },
            ]}
            onChange={setView}
          />
        </>
      }
    >
      {summary ? (
        <TimeSeriesChart
          series={series}
          type={view === 'all' ? 'line' : 'bar'}
          stacked={view === 'bySource'}
          yLabel={copy.yLabel}
          caption={caption}
          formatTime={formatTick}
          formatValue={formatCompact}
          yRange={{ min: 0 }}
          tooltipExtra={tooltipExtra}
          onSelectRange={onZoom}
          {...(noActivity(summary) ? { emptyText: copy.empty } : {})}
        />
      ) : error ? (
        <ErrorState error={error} variant="block" panel={pipelineCopy.pipeline.panels.throughput} onRetry={onRetry} />
      ) : (
        <PanelSkeleton variant="chart" />
      )}
    </Card>
  );
}
