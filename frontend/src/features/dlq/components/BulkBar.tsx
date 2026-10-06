import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import type { BulkKind } from '@/features/dlq/model';
import type { DlqTab } from '@/features/dlq/search';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';

const copy = dlqCopy.dlq;

interface BulkBarProps {
  tab: DlqTab;
  count: number;
  /** Requests done of those sent (§6); the buttons rest meanwhile. */
  progress?: { done: number; total: number };
  onAction: (kind: BulkKind) => void;
  onClear: () => void;
}

/** Sticks to the bottom of the list while records are selected (§4). */
export function BulkBar({ tab, count, progress, onAction, onClear }: BulkBarProps) {
  return (
    <div
      role="region"
      aria-label={copy.selected(count)}
      className="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-2 rounded-b-lg border-t border-border bg-card px-3 py-2 shadow-[0_-4px_8px_rgb(0_0_0/6%)]"
    >
      {progress ? (
        <div className="flex w-full items-center gap-3" role="status">
          <Progress value={(progress.done / Math.max(progress.total, 1)) * 100} className="flex-1" />
          <span className="text-sm tabular-nums">{copy.bulk.progress(progress.done, progress.total)}</span>
        </div>
      ) : (
        <>
          <span className="text-sm font-medium">{copy.selected(count)}</span>
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={onClear}>
              {en.common.cancel}
            </Button>
            {tab === 'confirm' ? (
              <Button
                size="sm"
                onClick={() => {
                  onAction('confirm');
                }}
              >
                {copy.bulk.confirmReplay}
              </Button>
            ) : (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  className="text-tone-danger-fg"
                  onClick={() => {
                    onAction('discard');
                  }}
                >
                  {copy.bulk.discardSelected}
                </Button>
                <Button
                  size="sm"
                  onClick={() => {
                    onAction('replay');
                  }}
                >
                  {copy.bulk.replaySelected}
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
