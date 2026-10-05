import { useEffect, useId, useRef, useState } from 'react';

import type { Chart } from '@/components/charts/echarts';
import { resolveColor as resolve, useEcharts } from '@/components/charts/use-echarts';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { useReducedMotion } from '@/lib/use-reduced-motion';

export interface TimeSeries {
  name: string;
  /** A CSS colour or `var(--token)`; default the first chart colour. */
  color?: string;
  /** Comparison series ("Previous period"): dashed, no area (DOC-35 §7). */
  dashed?: boolean;
  points: { t: string; v: number | null }[];
  /** Overrides the chart's `type` for this series: bars of delay under a line of on-time, say. */
  type?: 'line' | 'bar';
  /** Drawn against the second value axis (`y2`). */
  secondary?: boolean;
}

/** A second value axis on the right, for series in another unit. */
export interface SecondaryAxis {
  label: string;
  formatValue: (v: number) => string;
  range?: { min?: number; max?: number };
}

interface TimeSeriesChartProps {
  series: TimeSeries[];
  yLabel: string;
  stacked?: boolean;
  type?: 'line' | 'bar';
  height?: number;
  /** Accessible summary of what the chart shows (DOC-37 §5). */
  caption: string;
  /** Labels of the time axis and the table, in the agency's zone (src/lib/time.ts). */
  formatTime: (t: string) => string;
  formatValue?: (v: number) => string;
  /** Fixes the value axis, e.g. 0–100 for percentages. */
  yRange?: { min?: number; max?: number };
  /** For series marked `secondary`. */
  y2?: SecondaryAxis;
  /** The smallest gap between time-axis labels, e.g. one day for series by service date. */
  minIntervalMs?: number;
}

/** Series with at most this many points mark each one. */
const SYMBOLS_UP_TO = 40;

/**
 * A time series on ECharts (DOC-35 §5.7, §7): 2 px lines with a light area, dashed comparison series, gaps for missing
 * values, colours from the tokens, and a table view of the same numbers. ECharts loads on first render.
 */
export function TimeSeriesChart({
  series,
  yLabel,
  stacked = false,
  type = 'line',
  height = 220,
  caption,
  formatTime,
  formatValue = (v) => String(v),
  yRange,
  y2,
  minIntervalMs,
}: TimeSeriesChartProps) {
  const captionId = useId();
  const [asTable, setAsTable] = useState(false);
  const { container, chart, version } = useEcharts(!asTable);
  /** The instance that has been drawn once: only the first draw animates (DOC-35 §7). */
  const drawn = useRef<Chart | undefined>(undefined);
  const reducedMotion = useReducedMotion();

  useEffect(() => {
    const element = container.current;
    const instance = chart.current;
    if (!element || !instance) return;
    const token = (name: string) => resolve(element, `var(${name})`);
    const font = { fontFamily: getComputedStyle(element).fontFamily, fontSize: 12, color: token('--muted-foreground') };
    const valueAxis = (name: string, format: (v: number) => string, min: number | undefined, max?: number) => ({
      type: 'value',
      name,
      nameTextStyle: font,
      min,
      max,
      axisLabel: { ...font, formatter: (value: number) => format(value) },
      splitLine: { lineStyle: { color: token('--border'), type: 'dashed' } },
    });
    instance.setOption(
      {
        animation: drawn.current !== instance && !reducedMotion,
        grid: { left: 8, right: 12, top: 12, bottom: 8 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: token('--popover'),
          borderColor: token('--border'),
          textStyle: { ...font, color: token('--popover-foreground') },
          valueFormatter: (value: unknown) => (typeof value === 'number' ? formatValue(value) : en.kv.empty),
          axisPointer: { label: { formatter: ({ value }: { value: unknown }) => formatTime(String(value)) } },
        },
        xAxis: {
          type: 'time',
          minInterval: minIntervalMs,
          axisLine: { lineStyle: { color: token('--border') } },
          axisTick: { show: false },
          axisLabel: {
            ...font,
            formatter: (value: number) => formatTime(new Date(value).toISOString()),
            hideOverlap: true,
          },
          splitLine: { show: false },
        },
        yAxis: [
          valueAxis(yLabel, formatValue, yRange?.min ?? (type === 'bar' ? 0 : undefined), yRange?.max),
          ...(y2
            ? [{ ...valueAxis(y2.label, y2.formatValue, y2.range?.min, y2.range?.max), splitLine: { show: false } }]
            : []),
        ],
        series: series.map((s) => {
          const color = resolve(element, s.color ?? (s.dashed ? 'var(--muted-foreground)' : 'var(--chart-1)'));
          const seriesType = s.type ?? type;
          const format = s.secondary && y2 ? y2.formatValue : formatValue;
          return {
            name: s.name,
            type: seriesType,
            yAxisIndex: s.secondary && y2 ? 1 : 0,
            stack: stacked ? 'total' : undefined,
            tooltip: { valueFormatter: (value: unknown) => (typeof value === 'number' ? format(value) : en.kv.empty) },
            // Sparse series (days of a period) show their points; a lone value would not draw a line at all.
            showSymbol: s.points.length <= SYMBOLS_UP_TO,
            connectNulls: false,
            data: s.points.map((p) => [p.t, p.v]),
            itemStyle: { color },
            lineStyle: { width: 2, type: s.dashed ? 'dashed' : 'solid', color },
            areaStyle: seriesType === 'line' && !s.dashed && !s.secondary ? { color, opacity: 0.1 } : undefined,
          };
        }),
      },
      { notMerge: true },
    );
    drawn.current = instance;
  }, [
    series,
    yLabel,
    stacked,
    type,
    formatTime,
    formatValue,
    yRange,
    y2,
    minIntervalMs,
    reducedMotion,
    version,
    chart,
    container,
  ]);

  const times = [...new Set(series.flatMap((s) => s.points.map((p) => p.t)))].sort();
  return (
    <figure className="flex flex-col gap-2" aria-describedby={captionId}>
      <p id={captionId} className="sr-only">
        {caption}
      </p>
      {asTable ? (
        <div className="max-h-[260px] overflow-auto">
          <table className="w-full text-sm tabular-nums">
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="py-1 pr-3 font-medium">{en.chart.time}</th>
                {series.map((s) => (
                  <th key={s.name} className="py-1 pr-3 text-right font-medium">
                    {s.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {times.map((t) => (
                <tr key={t} className="border-t border-border">
                  <td className="py-1 pr-3">{formatTime(t)}</td>
                  {series.map((s) => {
                    const value = s.points.find((p) => p.t === t)?.v;
                    const format = s.secondary && y2 ? y2.formatValue : formatValue;
                    return (
                      <td key={s.name} className="py-1 pr-3 text-right">
                        {value === null || value === undefined ? en.kv.empty : format(value)}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div ref={container} role="img" aria-label={caption} style={{ height }} className="w-full" />
      )}
      <div className="flex items-center justify-between gap-3">
        {series.length > 1 ? (
          <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
            {series.map((s) => (
              <li key={s.name} className="inline-flex items-center gap-1.5">
                {(s.type ?? type) === 'bar' ? (
                  <span
                    aria-hidden="true"
                    className="size-2.5 rounded-[2px]"
                    style={{ backgroundColor: s.color ?? 'var(--chart-1)' }}
                  />
                ) : (
                  <span
                    aria-hidden="true"
                    className="w-3.5 border-t-2"
                    style={{
                      borderColor: s.color ?? (s.dashed ? 'var(--muted-foreground)' : 'var(--chart-1)'),
                      borderStyle: s.dashed ? 'dashed' : 'solid',
                    }}
                  />
                )}
                {s.name}
              </li>
            ))}
          </ul>
        ) : (
          <span />
        )}
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            setAsTable((value) => !value);
          }}
        >
          {asTable ? en.common.viewAsChart : en.common.viewAsTable}
        </Button>
      </div>
    </figure>
  );
}
