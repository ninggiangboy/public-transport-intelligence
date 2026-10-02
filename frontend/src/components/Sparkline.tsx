import { useId } from 'react';

import type { Tone } from '@/components/tone';
import { en } from '@/i18n/en';

interface SparklineProps {
  points: number[];
  /** Accessible name of the chart. */
  label: string;
  tone?: Tone;
  /** Fill under the line at 12%. */
  area?: boolean;
}

const WIDTH = 100;
const HEIGHT = 28;
const PAD = 2;

/** Plain-SVG trend line, no chart library (DOC-35 §5.7). Stretches to the width of its container. */
export function Sparkline({ points, label, tone, area = false }: SparklineProps) {
  const gradientId = useId();
  const stroke = tone ? `var(--tone-${tone}-solid)` : 'var(--primary)';
  if (points.length === 0) {
    return <span role="img" aria-label={`${label}: ${en.sparkline.empty}`} className="block h-7 w-full" />;
  }
  const min = Math.min(...points);
  const max = Math.max(...points);
  const span = max - min || 1;
  const step = points.length > 1 ? (WIDTH - PAD * 2) / (points.length - 1) : 0;
  const coords = points.map((value, index) => {
    const x = points.length > 1 ? PAD + index * step : WIDTH / 2;
    const y = HEIGHT - PAD - ((value - min) / span) * (HEIGHT - PAD * 2);
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  });
  const line = coords.join(' ');
  const areaPath = `M${coords[0] ?? ''} L${coords.join(' L')} L${(PAD + (points.length - 1) * step).toFixed(2)},${HEIGHT} L${PAD},${HEIGHT} Z`;
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      preserveAspectRatio="none"
      className="block h-7 w-full overflow-visible"
    >
      {area ? (
        <>
          <defs>
            <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor={stroke} stopOpacity="0.12" />
              <stop offset="1" stopColor={stroke} stopOpacity="0.02" />
            </linearGradient>
          </defs>
          <path d={areaPath} fill={`url(#${gradientId})`} />
        </>
      ) : null}
      <polyline
        points={line}
        fill="none"
        stroke={stroke}
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}
