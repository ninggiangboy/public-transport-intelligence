import { Sparkles } from 'lucide-react';

import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { Button } from '@/components/ui/button';
import { alertsCopy } from '@/i18n/alerts';
import { actorName } from '@/lib/format';

export interface DispatchSuggestion {
  action: string;
  actionConfidence: number;
  lowConfidence?: boolean;
  modelVersion?: string;
  operatorFeedback?: string;
  feedbackBy?: string;
}

interface DispatchSuggestionCardProps {
  suggestion: DispatchSuggestion;
  /** Operators only (E-18); without it the buttons are left out. */
  onFeedback?: (feedback: 'accepted' | 'ignored') => void;
  /** A feedback request is in flight. */
  busy?: boolean;
}

/**
 * The AI's dispatch suggestion for a bunching episode, with Accept / Dismiss for operators (DOC-24, E-17, E-18). Shared
 * by the alert feed and the live map.
 */
export function DispatchSuggestionCard({ suggestion, onFeedback, busy = false }: DispatchSuggestionCardProps) {
  const labels = alertsCopy.alerts.detailLabels;
  const action = alertsCopy.dispatchAction.full[suggestion.action] ?? suggestion.action;
  const feedback = suggestion.operatorFeedback;
  return (
    <Callout tone="primary" icon={<Sparkles className="size-4" strokeWidth={1.75} />} title={labels.suggestedAction}>
      <p className="text-foreground">{action}</p>
      <div className="mt-2 flex flex-wrap items-center gap-3">
        <ConfidenceChip
          value={suggestion.actionConfidence}
          lowConfidence={suggestion.lowConfidence}
          modelVersion={suggestion.modelVersion}
        />
        {feedback ? (
          <span className="text-xs text-muted-foreground">
            {suggestion.feedbackBy
              ? alertsCopy.alerts.activity.feedback(
                  alertsCopy.operatorFeedback[feedback] ?? feedback,
                  actorName(suggestion.feedbackBy),
                )
              : (alertsCopy.operatorFeedback[feedback] ?? feedback)}
          </span>
        ) : onFeedback ? (
          <span className="flex gap-2">
            <Button
              size="sm"
              disabled={busy}
              onClick={() => {
                onFeedback('accepted');
              }}
            >
              {alertsCopy.alerts.actions.accept}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => {
                onFeedback('ignored');
              }}
            >
              {alertsCopy.alerts.actions.dismiss}
            </Button>
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">{alertsCopy.operatorFeedback.none}</span>
        )}
      </div>
      {suggestion.modelVersion ? (
        <p className="mt-1.5 font-mono text-xs text-muted-foreground">{suggestion.modelVersion}</p>
      ) : null}
    </Callout>
  );
}
