import { useEffect, useId, useRef, useState, type PointerEvent } from 'react';

import type { Chart } from '@/components/charts/echarts';
import { resolveColor as resolve, useEcharts } from '@/components/charts/use-echarts';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { useReducedMotion } from '@/lib/use-reduced-motion';
import { cn } from '@/lib/utils';

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
  /** More lines of the tooltip at time `t`, under the series values: batches, p95… */
  tooltipExtra?: (t: string) => { label: string; value: string }[];
  /** Dragging across the plot picks a range (epoch ms); the picker of the screen is the keyboard way to do the same. */
  onSelectRange?: (from: number, to: number) => void;
  /** Over the plot, which keeps its axes: "No streaming activity in this period". */
  emptyText?: string;
}

/** A drag shorter than this is a click. */
const MIN_DRAG_PX = 8;

const escapeHtml = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

interface TooltipParam {
  seriesName?: string;
  marker?: unknown;
  value?: unknown;
  seriesIndex?: number;
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
  tooltipExtra,
  onSelectRange,
  emptyText,
}: TimeSeriesChartProps) {
  const captionId = useId();
  const [asTable, setAsTable] = useState(false);
  /** The dragged span in px from the left of the plot. */
  const [drag, setDrag] = useState<{ start: number; end: number } | undefined>(undefined);
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
          ...(tooltipExtra
            ? {
                formatter: (params: unknown) => {
                  const list = (Array.isArray(params) ? params : [params]) as TooltipParam[];
                  const first = list[0]?.value;
                  const t = Array.isArray(first) ? String(first[0]) : '';
                  const line = (marker: string, label: string, value: string) =>
                    `<div style="display:flex;gap:12px;justify-content:space-between"><span>${marker}${escapeHtml(label)}</span><b>${escapeHtml(value)}</b></div>`;
                  const values = list.map((p) => {
                    const v = Array.isArray(p.value) ? (p.value[1] as unknown) : undefined;
                    const s = series[p.seriesIndex ?? 0];
                    const format = s?.secondary && y2 ? y2.formatValue : formatValue;
                    return line(
                      typeof p.marker === 'string' ? p.marker : '',
                      p.seriesName ?? '',
                      typeof v === 'number' ? format(v) : en.kv.empty,
                    );
                  });
                  const extra = t ? tooltipExtra(t).map((row) => line('', row.label, row.value)) : [];
                  return [`<div>${escapeHtml(t ? formatTime(t) : '')}</div>`, ...values, ...extra].join('');
                },
              }
            : {}),
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
    tooltipExtra,
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
        <div
          className={cn('relative', onSelectRange && 'cursor-crosshair select-none')}
          {...(onSelectRange
            ? {
                onPointerDown: (event: PointerEvent<HTMLDivElement>) => {
                  if (event.button !== 0) return;
                  const x = event.clientX - event.currentTarget.getBoundingClientRect().left;
                  event.currentTarget.setPointerCapture(event.pointerId);
                  setDrag({ start: x, end: x });
                },
                onPointerMove: (event: PointerEvent<HTMLDivElement>) => {
                  if (!drag) return;
                  const x = event.clientX - event.currentTarget.getBoundingClientRect().left;
                  setDrag({ start: drag.start, end: x });
                },
                onPointerUp: () => {
                  const span = drag;
                  setDrag(undefined);
                  const instance = chart.current;
                  if (!span || !instance || Math.abs(span.end - span.start) < MIN_DRAG_PX) return;
                  const at = (x: number) => {
                    const value: unknown = instance.convertFromPixel({ gridIndex: 0 }, [x, 0]);
                    return Array.isArray(value) ? Number(value[0]) : Number.NaN;
                  };
                  const a = at(Math.min(span.start, span.end));
                  const b = at(Math.max(span.start, span.end));
                  if (Number.isFinite(a) && Number.isFinite(b) && a < b) onSelectRange(a, b);
                },
                onPointerCancel: () => {
                  setDrag(undefined);
                },
              }
            : {})}
        >
          <div ref={container} role="img" aria-label={caption} style={{ height }} className="w-full" />
          {drag ? (
            <span
              aria-hidden="true"
              className="pointer-events-none absolute inset-y-3 border-x border-primary bg-primary/10"
              style={{ left: Math.min(drag.start, drag.end), width: Math.abs(drag.end - drag.start) }}
            />
          ) : null}
          {emptyText ? (
            <p className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-muted-foreground">
              {emptyText}
            </p>
          ) : null}
        </div>
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
