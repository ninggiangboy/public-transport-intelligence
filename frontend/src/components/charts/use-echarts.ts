import { useEffect, useRef, useState } from 'react';

import type { Chart } from '@/components/charts/echarts';

/** Resolves `var(--token)` against the element, because the canvas cannot read CSS variables. */
export function resolveColor(element: HTMLElement, color: string): string {
  const match = /^var\((--[\w-]+)\)$/.exec(color);
  return match?.[1] ? getComputedStyle(element).getPropertyValue(match[1]).trim() || color : color;
}

/**
 * An ECharts instance on `container`, loaded on first use (its own chunk, DOC-34 §7), resized with the container and
 * disposed with it. `version` changes when the instance is ready and when the theme changes, so the caller's effect
 * that calls `setOption` depends on it. Pass `enabled = false` while the chart is swapped for its table view.
 */
export function useEcharts(enabled: boolean) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<Chart | undefined>(undefined);
  const [version, setVersion] = useState(0);

  // The <html> class changes with the theme; the canvas then needs its colours again.
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setVersion((n) => n + 1);
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => {
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    const element = container.current;
    if (!element || !enabled) return;
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
      setVersion((n) => n + 1);
    });
    return () => {
      disposed = true;
      resize?.disconnect();
      chart.current?.dispose();
      chart.current = undefined;
    };
  }, [enabled]);

  return { container, chart, version };
}
