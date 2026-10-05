import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';

import type { components } from '@/api/generated/schema';
import { Card } from '@/components/Card';
import { HeatmapChart, type HeatCell } from '@/components/charts/HeatmapChart';
import { TimeSeriesChart, type TimeSeries } from '@/components/charts/TimeSeriesChart';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SegmentedControl } from '@/components/SegmentedControl';
import { directionName } from '@/features/scorecard/display';
import { heatCells, rangeInstants, type DayRange } from '@/features/scorecard/model';
import { delaysQuery } from '@/features/scorecard/queries';
import { BUCKETS, type Bucket, type RouteScorecardSearch } from '@/features/scorecard/search';
import { scorecardCopy } from '@/i18n/scorecard';
import { formatCount, formatDelaySeconds, formatPercent } from '@/lib/format';
import { formatDateTime, formatHourOfDay, formatServiceDate, formatWeekday } from '@/lib/time';

type RouteDetail = components['schemas']['RouteDetailResponse'];
type Day = components['schemas']['Day'];
type DelayBucket = components['schemas']['DelayBucketResponse'];

const DAY_MS = 86_400_000;
const copy = scorecardCopy.scorecard;
const delays = copy.delays;
const WEEKDAYS = Array.from({ length: 7 }, (_, index) => formatWeekday(index));
const HOURS = Array.from({ length: 24 }, (_, hour) => formatHourOfDay(hour));
const BOTH = 'both';
const formatOtp = (v: number) => formatPercent(v / 100);
const formatDayTick = (t: string) => formatServiceDate(t.slice(0, 10));

interface DelaysTabProps {
  route: RouteDetail;
  range: DayRange;
  timezone: string;
  /** E-14 `daily` of the route, for "Daily on-time performance". */
  daily: readonly Day[];
  otpLoading: boolean;
  search: RouteScorecardSearch;
  onSearch: (change: Partial<RouteScorecardSearch>) => void;
}

/** Average, median and 90th percentile of E-03 by hour. */
function lineSeries(items: readonly DelayBucket[]): TimeSeries[] {
  const points = (pick: (item: DelayBucket) => number) =>
    items.flatMap((item) => (item.bucketStart === undefined ? [] : [{ t: item.bucketStart, v: pick(item) }]));
  return [
    { name: delays.average, color: 'var(--chart-1)', points: points((item) => item.avgDelaySeconds) },
    { name: delays.median, color: 'var(--chart-2)', points: points((item) => item.medianDelaySeconds) },
    { name: delays.p90, color: 'var(--chart-3)', points: points((item) => item.p90DelaySeconds) },
  ];
}

/** Tab "Delays" (§3, §4): E-03 as a 7 × 24 heatmap, by hour or by day, then the route's daily on-time. */
export function DelaysTab({ route, range, timezone, daily, otpLoading, search, onSearch }: DelaysTabProps) {
  const bucket: Bucket = search.bucket ?? 'hour-of-week';
  const instants = rangeInstants(range, timezone);
  const query = useQuery(
    delaysQuery(route.routeId, {
      ...instants,
      bucket,
      ...(search.dir === undefined ? {} : { directionId: search.dir }),
    }),
  );
  const items = query.data?.data.items;
  const formatHourTick = useCallback(
    (t: string) => formatDateTime(t, { timeZone: timezone, showZone: false }),
    [timezone],
  );
  const cells = useMemo(() => heatCells(items ?? []), [items]);
  const describe = useCallback(
    (cell: HeatCell & { value: number }) => {
      const item = items?.find((entry) => entry.dayOfWeek === cell.row + 1 && entry.hourOfDay === cell.col);
      return [
        `${WEEKDAYS[cell.row] ?? ''} · ${HOURS[cell.col] ?? ''}`,
        delays.tooltip.average(formatDelaySeconds(cell.value)),
        ...(item
          ? [
              delays.tooltip.median(formatDelaySeconds(item.medianDelaySeconds)),
              delays.tooltip.p90(formatDelaySeconds(item.p90DelaySeconds)),
              delays.tooltip.observations(formatCount(item.observationCount)),
              delays.tooltip.onTime(formatOtp(item.onTimePercentage)),
            ]
          : []),
      ];
    },
    [items],
  );
  const series = useMemo((): TimeSeries[] => {
    if (!items || bucket === 'hour-of-week') return [];
    if (bucket === 'hour') return lineSeries(items);
    const at = (item: DelayBucket) => (item.serviceDate ? `${item.serviceDate}T12:00:00Z` : undefined);
    const points = (pick: (item: DelayBucket) => number) =>
      items.flatMap((item) => {
        const t = at(item);
        return t === undefined ? [] : [{ t, v: pick(item) }];
      });
    return [
      { name: delays.average, type: 'bar', color: 'var(--chart-1)', points: points((item) => item.avgDelaySeconds) },
      {
        name: delays.onTime,
        type: 'line',
        secondary: true,
        color: 'var(--chart-2)',
        points: points((item) => item.onTimePercentage),
      },
    ];
  }, [items, bucket]);
  const otpSeries = useMemo(
    (): TimeSeries[] => [
      {
        name: delays.onTime,
        points: [...daily]
          .sort((a, b) => a.serviceDate.localeCompare(b.serviceDate))
          .map((day) => ({ t: `${day.serviceDate}T12:00:00Z`, v: day.otpPercentage })),
      },
    ],
    [daily],
  );

  const panel = () =>
    query.isError && !query.data ? (
      <ErrorState error={query.error} variant="block" panel={copy.panels.delays} onRetry={() => void query.refetch()} />
    ) : !items ? (
      <PanelSkeleton variant="chart" />
    ) : items.length === 0 || (bucket === 'hour-of-week' && cells.length === 0) ? (
      <EmptyState title={copy.chartEmpty} />
    ) : bucket === 'hour-of-week' ? (
      <HeatmapChart
        rows={WEEKDAYS}
        cols={HOURS}
        cells={cells}
        scale="delay"
        valueFormatter={formatDelaySeconds}
        describe={describe}
        caption={delays.heatCaption(range.from, range.to)}
      />
    ) : (
      <TimeSeriesChart
        series={series}
        yLabel={delays.yLabel}
        type={bucket === 'day' ? 'bar' : 'line'}
        caption={bucket === 'day' ? delays.dayCaption(range.from, range.to) : delays.lineCaption(range.from, range.to)}
        formatTime={bucket === 'day' ? formatDayTick : formatHourTick}
        {...(bucket === 'day' ? { minIntervalMs: DAY_MS } : {})}
        formatValue={formatDelaySeconds}
        {...(bucket === 'day' ? { y2: { label: delays.onTime, formatValue: formatOtp, range: { max: 100 } } } : {})}
      />
    );

  const directions = route.directions;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <span className="inline-flex items-center gap-2">
          <span className="text-label font-medium text-muted-foreground" aria-hidden="true">
            {copy.direction.label}
          </span>
          <SegmentedControl
            label={copy.direction.label}
            value={search.dir === undefined ? BOTH : String(search.dir)}
            options={[
              { value: BOTH, label: copy.direction.both },
              ...directions.map((d) => ({ value: String(d.directionId), label: directionName(d, d.directionId) })),
            ]}
            onChange={(value) => {
              onSearch({ dir: value === BOTH ? undefined : Number(value) });
            }}
          />
        </span>
        <span className="inline-flex items-center gap-2">
          <span className="text-label font-medium text-muted-foreground" aria-hidden="true">
            {delays.view}
          </span>
          <SegmentedControl<Bucket>
            label={delays.view}
            value={bucket}
            options={BUCKETS.map((value) => ({ value, label: delays.views[value] }))}
            onChange={(value) => {
              onSearch({ bucket: value === 'hour-of-week' ? undefined : value });
            }}
          />
        </span>
      </div>

      <Card title={bucket === 'hour-of-week' ? delays.heatTitle : delays.overTime}>{panel()}</Card>

      <Card title={delays.dailyOtp}>
        {otpLoading ? (
          <PanelSkeleton variant="chart" />
        ) : daily.length === 0 ? (
          <EmptyState title={copy.chartEmpty} />
        ) : (
          <TimeSeriesChart
            series={otpSeries}
            yLabel={delays.onTime}
            yRange={{ max: 100 }}
            caption={delays.dailyOtpCaption(range.from, range.to)}
            formatTime={formatDayTick}
            minIntervalMs={DAY_MS}
            formatValue={formatOtp}
          />
        )}
      </Card>
    </div>
  );
}
