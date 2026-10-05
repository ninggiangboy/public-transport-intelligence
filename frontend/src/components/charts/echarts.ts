// ECharts, by module (DOC-35 §7): only the charts and components the screens use; a screen that needs another
// registers it here. Charts load this file with a dynamic import, so it is its own chunk, checked against the ECharts
// budget of DOC-34 §7 (DR-106). Legends are HTML, so LegendComponent is not needed; the heatmap needs a visualMap, the
// piecewise one, hidden, that maps values to the `--heat-*` steps.
import { BarChart, HeatmapChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, VisualMapPiecewiseComponent } from 'echarts/components';
import { init, use as register, type EChartsType } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';

register([
  LineChart,
  BarChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  VisualMapPiecewiseComponent,
  CanvasRenderer,
]);

export type Chart = Pick<EChartsType, 'setOption' | 'resize' | 'dispose'>;

export function initChart(element: HTMLElement): Chart {
  return init(element, undefined, { renderer: 'canvas' });
}
