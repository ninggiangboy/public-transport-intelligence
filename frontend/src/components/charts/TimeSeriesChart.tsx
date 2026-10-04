import { useEffect, useId, useRef, useState } from 'react';

import type { Chart } from '@/components/charts/echarts';
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
}

/** Series with at most this many points mark each one. */
const SYMBOLS_UP_TO = 40;

/** Resolves `var(--token)` against the element, because the canvas cannot read CSS variables. */
function resolve(element: HTMLElement, color: string): string {
  const match = /^var\((--[\w-]+)\)$/.exec(color);
  return match?.[1] ? getComputedStyle(element).getPropertyValue(match[1]).trim() || color : color;
}

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
}: TimeSeriesChartProps) {
  const captionId = useId();
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<Chart | undefined>(undefined);
  const drawn = useRef(false);
  const [asTable, setAsTable] = useState(false);
  const [theme, setTheme] = useState(0);
  const reducedMotion = useReducedMotion();

  // The <html> class changes with the theme; the canvas then needs its colours again.
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setTheme((n) => n + 1);
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => {
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    const element = container.current;
    if (!element || asTable) return;
    let disposed = false;
    let resize: ResizeObserver | undefined;
    void import('@/components/charts/echarts').then(({ initChart }) => {
      if (disposed) return;
      const instance = initChart(element);
      chart.current = instance;
      resize = new ResizeObserver(() => {
        instance.resize();
      });
      resize.observe(element);
      setTheme((n) => n + 1);
    });
    return () => {
      disposed = true;
      resize?.disconnect();
      chart.current?.dispose();
      chart.current = undefined;
      drawn.current = false;
    };
  }, [asTable]);

  useEffect(() => {
    const element = container.current;
    const instance = chart.current;
    if (!element || !instance) return;
    const token = (name: string) => resolve(element, `var(${name})`);
    const font = { fontFamily: getComputedStyle(element).fontFamily, fontSize: 12, color: token('--muted-foreground') };
    instance.setOption(
      {
        animation: !drawn.current && !reducedMotion,
        grid: { left: 8, right: 12, top: 12, bottom: 8, containLabel: true },
        tooltip: {
          trigger: 'axis',
          backgroundColor: token('--popover'),
          borderColor: token('--border'),
          textStyle: { ...font, color: token('--popover-foreground') },
          valueFormatter: (value: unknown) => (typeof value === 'number' ? formatValue(value) : '—'),
          axisPointer: { label: { formatter: ({ value }: { value: unknown }) => formatTime(String(value)) } },
        },
        xAxis: {
          type: 'time',
          axisLine: { lineStyle: { color: token('--border') } },
          axisTick: { show: false },
          axisLabel: {
            ...font,
            formatter: (value: number) => formatTime(new Date(value).toISOString()),
            hideOverlap: true,
          },
          splitLine: { show: false },
        },
        yAxis: {
          type: 'value',
          name: yLabel,
          nameTextStyle: font,
          min: yRange?.min ?? (type === 'bar' ? 0 : undefined),
          max: yRange?.max,
          axisLabel: { ...font, formatter: (value: number) => formatValue(value) },
          splitLine: { lineStyle: { color: token('--border'), type: 'dashed' } },
        },
        series: series.map((s) => {
          const color = resolve(element, s.color ?? (s.dashed ? 'var(--muted-foreground)' : 'var(--chart-1)'));
          return {
            name: s.name,
            type,
            stack: stacked ? 'total' : undefined,
            // Sparse series (days of a period) show their points; a lone value would not draw a line at all.
            showSymbol: s.points.length <= SYMBOLS_UP_TO,
            connectNulls: false,
            data: s.points.map((p) => [p.t, p.v]),
            itemStyle: { color },
            lineStyle: { width: 2, type: s.dashed ? 'dashed' : 'solid', color },
            areaStyle: type === 'line' && !s.dashed ? { color, opacity: 0.1 } : undefined,
          };
        }),
      },
      { notMerge: true },
    );
    drawn.current = true;
  }, [series, yLabel, stacked, type, formatTime, formatValue, yRange, reducedMotion, theme]);

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
                    return (
                      <td key={s.name} className="py-1 pr-3 text-right">
                        {value === null || value === undefined ? en.kv.empty : formatValue(value)}
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
                <span
                  aria-hidden="true"
                  className="w-3.5 border-t-2"
                  style={{
                    borderColor: s.color ?? (s.dashed ? 'var(--muted-foreground)' : 'var(--chart-1)'),
                    borderStyle: s.dashed ? 'dashed' : 'solid',
                  }}
                />
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
