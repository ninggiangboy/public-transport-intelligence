import { toneClasses, type Tone } from '@/components/tone';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { en } from '@/i18n/en';
import { formatPercentWhole } from '@/lib/format';
import { cn } from '@/lib/utils';

/** Below this an AI answer is "low confidence" unless the API says otherwise (DOC-35 §3.3, FR-09.6). */
export const LOW_CONFIDENCE_THRESHOLD = 0.6;
const HIGH_CONFIDENCE_THRESHOLD = 0.8;

type EtaLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

/** ETA confidence (`level`) or AI confidence (`value`), never both. */
export type ConfidenceChipProps = (
  { level: EtaLevel; value?: never; modelVersion?: never } | { value: number; level?: never; modelVersion?: string }
) & {
  /** ETA only: past trips behind the prediction, shown in the tooltip. */
  sampleCount?: number;
  /** From the API when present; otherwise `value < 0.6`. The API's flag wins. */
  lowConfidence?: boolean;
};

interface Presentation {
  tone: Tone;
  bars: 0 | 1 | 2 | 3;
  text: string;
  help: string;
}

function present(props: ConfidenceChipProps): Presentation {
  if (props.level !== undefined) {
    const { level, sampleCount } = props;
    const tone: Tone =
      level === 'HIGH' ? 'success' : level === 'MEDIUM' ? 'teal' : level === 'LOW' ? 'warning' : 'neutral';
    const bars = level === 'HIGH' ? 3 : level === 'MEDIUM' ? 2 : level === 'LOW' ? 1 : 0;
    const help = level === 'NONE' || sampleCount === undefined ? en.help.etaNone : en.help.etaConfidence(sampleCount);
    return { tone, bars, text: en.confidence.eta[level], help };
  }
  const percent = formatPercentWhole(props.value);
  const low = props.lowConfidence ?? props.value < LOW_CONFIDENCE_THRESHOLD;
  const tone: Tone = low ? 'warning' : props.value >= HIGH_CONFIDENCE_THRESHOLD ? 'success' : 'teal';
  const bars = low ? 1 : props.value >= HIGH_CONFIDENCE_THRESHOLD ? 3 : 2;
  return {
    tone,
    bars,
    text: low ? en.confidence.aiLow(percent) : en.confidence.ai(percent),
    help: en.help.aiConfidence(percent, props.modelVersion),
  };
}

const BAR_HEIGHTS = ['h-[5px]', 'h-2', 'h-3'] as const;

/** Three rising bars plus text, no background (DOC-35 §5.1). The bars and the text carry the level, not colour alone. */
export function ConfidenceChip(props: ConfidenceChipProps) {
  const { tone, bars, text, help } = present(props);
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span
          tabIndex={0}
          className={cn('inline-flex items-center gap-1.5 text-xs font-medium', toneClasses(tone).text)}
        >
          <span className="flex h-3 items-end gap-px" aria-hidden="true">
            {BAR_HEIGHTS.map((height, index) => (
              <span
                key={height}
                className={cn('w-[3px] rounded-[1px]', height, index < bars ? toneClasses(tone).solid : 'bg-muted-2')}
              />
            ))}
          </span>
          {text}
        </span>
      </TooltipTrigger>
      <TooltipContent>{help}</TooltipContent>
    </Tooltip>
  );
}
