import { useEffect, useId, useRef, useState } from 'react';

import type { Chart } from '@/components/charts/echarts';
import { HEAT_LEVELS, heatPieces, type HeatScale } from '@/components/charts/heat';
import { resolveColor, useEcharts } from '@/components/charts/use-echarts';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { useReducedMotion } from '@/lib/use-reduced-motion';

export interface HeatCell {
  row: number;
  col: number;
  value: number | null;
  count?: number;
}

interface HeatmapChartProps {
  /** Labels of the rows, top to bottom: "Mon" … "Sun". */
  rows: string[];
  /** Labels of the columns, left to right: "12 AM" … "11 PM". */
  cols: string[];
  cells: HeatCell[];
  scale: HeatScale;
  valueFormatter: (v: number) => string;
  /** Lines of the tooltip of a cell with data; default the row, column and value. */
  describe?: (cell: HeatCell & { value: number }) => string[];
  /** Accessible summary of what the chart shows (DOC-37 §5). */
  caption: string;
  height?: number;
}

/**
 * A rows × columns heatmap on ECharts (DOC-35 §5.7, §7): cells rounded 3 px with 2 px gaps, coloured `--heat-1…7`
 * by a fixed scale through a hidden piecewise visualMap, empty cells left blank, a "Less → More late" legend and a table view of the same numbers.
 */
export function HeatmapChart({
  rows,
  cols,
  cells,
  scale,
  valueFormatter,
  describe,
  caption,
  height = 260,
}: HeatmapChartProps) {
  const captionId = useId();
  const [asTable, setAsTable] = useState(false);
  const { container, chart, version } = useEcharts(!asTable);
  const drawn = useRef<Chart | undefined>(undefined);
  const reducedMotion = useReducedMotion();

  useEffect(() => {
    const element = container.current;
    const instance = chart.current;
    if (!element || !instance) return;
    const token = (name: string) => resolveColor(element, `var(${name})`);
    const font = { fontFamily: getComputedStyle(element).fontFamily, fontSize: 12, color: token('--muted-foreground') };
    const byKey = new Map(cells.map((cell) => [`${cell.row}:${cell.col}`, cell]));
    // Cells without data stay out of the series and are left blank (DOC-35 §7).
    const data = cells.flatMap((cell) => (cell.value === null ? [] : [[cell.col, cell.row, cell.value]]));
    const tooltipText = (col: number, row: number) => {
      const cell = byKey.get(`${row}:${col}`);
      const where = `${rows[row] ?? ''} · ${cols[col] ?? ''}`;
      const value = cell?.value;
      if (cell === undefined || value === null || value === undefined) return [where, en.chart.noData];
      return describe ? describe({ ...cell, value }) : [where, valueFormatter(value)];
    };
    instance.setOption(
      {
        animation: drawn.current !== instance && !reducedMotion,
        grid: { left: 8, right: 8, top: 4, bottom: 4 },
        tooltip: {
          backgroundColor: token('--popover'),
          borderColor: token('--border'),
          textStyle: { ...font, color: token('--popover-foreground') },
          formatter: (params: { value: [number, number, unknown] }) =>
            tooltipText(params.value[0], params.value[1])
              .map((line) => line.replaceAll('&', '&amp;').replaceAll('<', '&lt;'))
              .join('<br/>'),
        },
        xAxis: {
          type: 'category',
          data: cols,
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { ...font, hideOverlap: true },
        },
        yAxis: {
          type: 'category',
          data: rows,
          inverse: true,
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: font,
        },
        visualMap: {
          type: 'piecewise',
          show: false,
          dimension: 2,
          pieces: heatPieces(scale).map(({ level, ...range }) => ({ ...range, color: token(`--heat-${level}`) })),
        },
        series: [
          {
            type: 'heatmap',
            data,
            itemStyle: { borderRadius: 3, borderWidth: 2, borderColor: token('--card') },
            emphasis: { itemStyle: { borderColor: token('--foreground'), borderWidth: 1 } },
          },
        ],
      },
      { notMerge: true },
    );
    drawn.current = instance;
  }, [rows, cols, cells, scale, valueFormatter, describe, reducedMotion, version, chart, container]);

  const valueAt = (row: number, col: number) =>
    cells.find((cell) => cell.row === row && cell.col === col)?.value ?? null;
  return (
    <figure className="flex flex-col gap-2" aria-describedby={captionId}>
      <p id={captionId} className="sr-only">
        {caption}
      </p>
      {asTable ? (
        <div className="max-h-[320px] overflow-auto">
          <table className="w-full text-xs tabular-nums">
            <thead>
              <tr className="text-left text-muted-foreground">
                <th className="sticky left-0 bg-card py-1 pr-3 font-medium">
                  <span className="sr-only">{en.chart.time}</span>
                </th>
                {cols.map((col) => (
                  <th key={col} scope="col" className="px-1.5 py-1 text-right font-medium whitespace-nowrap">
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, rowIndex) => (
                <tr key={row} className="border-t border-border">
                  <th scope="row" className="sticky left-0 bg-card py-1 pr-3 text-left font-medium">
                    {row}
                  </th>
                  {cols.map((col, colIndex) => {
                    const value = valueAt(rowIndex, colIndex);
                    return (
                      <td key={col} className="px-1.5 py-1 text-right whitespace-nowrap">
                        {value === null ? en.kv.empty : valueFormatter(value)}
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
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground" aria-hidden="true">
          {en.chart.heatLow[scale]}
          <span className="flex gap-0.5">
            {HEAT_LEVELS.map((level) => (
              <span
                key={level}
                className="size-2.5 rounded-[2px]"
                style={{ backgroundColor: `var(--heat-${level})` }}
              />
            ))}
          </span>
          {en.chart.heatHigh[scale]}
        </p>
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
