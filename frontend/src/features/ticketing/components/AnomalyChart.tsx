import { useCallback, useMemo } from 'react';

import { Card } from '@/components/Card';
import { TimeSeriesChart, type TimeSeries } from '@/components/charts/TimeSeriesChart';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { hourlyCounts } from '@/features/ticketing/model';
import type { ChartData } from '@/features/ticketing/queries';
import { TRIGGERS, type Trigger } from '@/features/ticketing/search';
import { alertsCopy } from '@/i18n/alerts';
import { ticketingCopy } from '@/i18n/ticketing';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCount } from '@/lib/format';
import { formatDateTime, formatTime } from '@/lib/time';

const copy = ticketingCopy.chart;
const COLORS: Record<Trigger, string> = {
  VOLUME: 'var(--chart-1)',
  REFUND_RATIO: 'var(--chart-3)',
  BOTH: 'var(--chart-6)',
};
const DAY_MS = 86_400_000;

interface AnomalyChartProps {
  data?: ChartData;
  error: unknown;
  onRetry: () => void;
  /** "No data · 18 min" at the end when the ticketing source is stale (§4). */
  noData?: string;
}

/** "Anomalies over time": anomalies per hour of `detectedAt`, stacked by trigger (§4). */
export function AnomalyChart({ data, error, onRetry, noData }: AnomalyChartProps) {
  const { timezone } = useBusinessClock();
  const counts = useMemo(() => (data ? hourlyCounts(data.items, data.range) : undefined), [data]);
  const series = useMemo<TimeSeries[]>(
    () =>
      counts
        ? TRIGGERS.map((trigger) => ({
            name: alertsCopy.ticketingTrigger[trigger] ?? trigger,
            color: COLORS[trigger],
            points: counts.hours.map((t, index) => ({ t, v: counts.byTrigger[trigger][index] ?? 0 })),
          }))
        : [],
    [counts],
  );
  const long = data ? Date.parse(data.range.to) - Date.parse(data.range.from) > DAY_MS : false;
  const formatTick = useCallback(
    (t: string) =>
      long
        ? formatDateTime(t, { timeZone: timezone, showZone: false })
        : formatTime(t, { timeZone: timezone, showZone: false }),
    [long, timezone],
  );

  const meta = [data?.truncated ? copy.truncated : undefined, noData].filter(Boolean).join(' · ');

  return (
    <Card title={copy.title} {...(meta ? { meta } : {})}>
      {error && !data ? (
        <ErrorState error={error} variant="block" panel={copy.panel} onRetry={onRetry} />
      ) : !data || !counts ? (
        <PanelSkeleton variant="chart" />
      ) : (
        <TimeSeriesChart
          series={series}
          type="bar"
          stacked
          height={160}
          yLabel={copy.yLabel}
          formatTime={formatTick}
          formatValue={formatCount}
          caption={copy.caption(
            data.items.length,
            formatDateTime(data.range.from, { timeZone: timezone, showZone: false }),
            formatDateTime(data.range.to, { timeZone: timezone }),
          )}
          {...(data.items.length === 0 ? { emptyText: copy.empty } : {})}
        />
      )}
    </Card>
  );
}
