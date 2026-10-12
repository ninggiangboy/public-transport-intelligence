import { CircleDashed } from 'lucide-react';

import { ConfidenceChip } from '@/components/ConfidenceChip';
import { SeverityBadge } from '@/components/SeverityBadge';
import { ToneBadge } from '@/components/ToneBadge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { toSeverity, type Anomaly } from '@/features/ticketing/model';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';

// Pieces shared by the table rows and the detail panel.

/** "Unclassified" in a neutral chip, with the reason in a tooltip (FR-09.7, DOC-37 §5 `help.unclassified`). */
export function UnclassifiedChip({ label = alertsCopy.ticketingCategory.unclassified ?? '' }: { label?: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex rounded-sm">
          <ToneBadge tone="neutral" icon={CircleDashed} size="sm" label={label} />
        </span>
      </TooltipTrigger>
      <TooltipContent>{en.help.unclassified}</TooltipContent>
    </Tooltip>
  );
}

/** AI category and its confidence; "Low confidence" in `warning` below 0.6 (FR-09.6, AC-3). */
export function CategoryCell({ anomaly }: { anomaly: Anomaly }) {
  if (!anomaly.category) return <UnclassifiedChip />;
  return (
    <span className="flex flex-col items-start gap-0.5">
      <span className="font-medium">{alertsCopy.ticketingCategory[anomaly.category] ?? anomaly.category}</span>
      {anomaly.categoryConfidence !== undefined ? (
        <ConfidenceChip
          value={anomaly.categoryConfidence}
          {...(anomaly.modelVersion ? { modelVersion: anomaly.modelVersion } : {})}
        />
      ) : null}
    </span>
  );
}

export function SeverityCell({ anomaly }: { anomaly: Anomaly }) {
  if (anomaly.severity === undefined) return <span className="text-muted-foreground">{en.kv.empty}</span>;
  return (
    <span className="flex flex-col items-start gap-0.5">
      <SeverityBadge severity={toSeverity(anomaly.severity)} size="sm" />
      {anomaly.severityConfidence !== undefined ? (
        <ConfidenceChip
          value={anomaly.severityConfidence}
          {...(anomaly.modelVersion ? { modelVersion: anomaly.modelVersion } : {})}
        />
      ) : null}
    </span>
  );
}
