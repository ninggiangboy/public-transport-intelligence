import { Sparkles } from 'lucide-react';

import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { Button } from '@/components/ui/button';
import { alertsCopy } from '@/i18n/alerts';
import { useBusinessClock } from '@/lib/business-clock';
import { actorName } from '@/lib/format';
import { formatTime } from '@/lib/time';

export interface DispatchSuggestion {
  action: string;
  actionConfidence: number;
  lowConfidence?: boolean;
  modelVersion?: string;
  operatorFeedback?: string;
  feedbackBy?: string;
  feedbackAt?: string;
}

interface DispatchSuggestionCardProps {
  suggestion: DispatchSuggestion;
  /**
   * Operators only (E-18); without it the buttons are left out. They stay after a feedback, the chosen one pressed, so
   * that the other one overrides it (UC-04 3b).
   */
  onFeedback?: (feedback: 'accepted' | 'ignored') => void;
  /** A feedback request is in flight. */
  busy?: boolean;
}

/**
 * The AI's dispatch suggestion for a bunching episode, with Accept / Dismiss for operators (DOC-24, E-17, E-18). Shared
 * by the alert feed and the live map.
 */
export function DispatchSuggestionCard({ suggestion, onFeedback, busy = false }: DispatchSuggestionCardProps) {
  const clock = useBusinessClock();
  const labels = alertsCopy.alerts.detailLabels;
  const action = alertsCopy.dispatchAction.full[suggestion.action] ?? suggestion.action;
  const feedback = suggestion.operatorFeedback;
  const feedbackLabel = feedback ? (alertsCopy.operatorFeedback[feedback] ?? feedback) : undefined;
  const status =
    feedbackLabel && suggestion.feedbackBy
      ? alertsCopy.alerts.feedbackBy(
          feedbackLabel,
          actorName(suggestion.feedbackBy),
          suggestion.feedbackAt ? formatTime(suggestion.feedbackAt, { timeZone: clock.timezone }) : undefined,
        )
      : feedbackLabel;
  const choice = (value: 'accepted' | 'ignored', label: string, variant: 'default' | 'outline') => (
    <Button
      size="sm"
      variant={feedback === undefined ? variant : feedback === value ? 'default' : 'outline'}
      aria-pressed={feedback === undefined ? undefined : feedback === value}
      disabled={busy}
      onClick={() => {
        if (feedback !== value) onFeedback?.(value);
      }}
    >
      {label}
    </Button>
  );
  return (
    <Callout tone="primary" icon={<Sparkles className="size-4" strokeWidth={1.75} />} title={labels.suggestedAction}>
      <p className="text-foreground">{action}</p>
      <div className="mt-2 flex flex-wrap items-center gap-3">
        <ConfidenceChip
          value={suggestion.actionConfidence}
          lowConfidence={suggestion.lowConfidence}
          modelVersion={suggestion.modelVersion}
        />
        {onFeedback ? (
          <span className="flex gap-2">
            {choice('accepted', alertsCopy.alerts.actions.accept, 'default')}
            {choice('ignored', alertsCopy.alerts.actions.dismiss, 'outline')}
          </span>
        ) : null}
        <span className="text-xs text-muted-foreground" aria-live="polite">
          {status ?? (onFeedback ? '' : alertsCopy.operatorFeedback.none)}
        </span>
      </div>
      {suggestion.modelVersion ? (
        <p className="mt-1.5 font-mono text-xs text-muted-foreground">{suggestion.modelVersion}</p>
      ) : null}
    </Callout>
  );
}
