import { LOW_CONFIDENCE_THRESHOLD } from '@/components/ConfidenceChip';
import { Progress } from '@/components/ui/progress';
import { en } from '@/i18n/en';
import { formatPercentWhole } from '@/lib/format';

interface ConfidenceMeterProps {
  /** Ratio in [0, 1]. */
  value: number;
  lowConfidence?: boolean;
  /** Accessible name; defaults to "Confidence". */
  label?: string;
}

/** Horizontal bar with the number beside it, for drawers and tables (DOC-35 §5.1). */
export function ConfidenceMeter({ value, lowConfidence, label = en.confidence.meterLabel }: ConfidenceMeterProps) {
  const low = lowConfidence ?? value < LOW_CONFIDENCE_THRESHOLD;
  const fill = low ? 'bg-tone-warning-solid' : 'bg-tone-success-solid';
  const percent = Math.round(Math.min(1, Math.max(0, value)) * 100);
  return (
    <div className="flex items-center gap-2">
      <Progress value={percent} aria-label={label} className="w-24" indicatorClassName={fill} />
      <span className="text-xs font-medium text-foreground-2 tabular-nums">{formatPercentWhole(value)}</span>
    </div>
  );
}
