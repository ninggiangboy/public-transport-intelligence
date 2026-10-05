import type { components } from '@/api/generated/schema';
import { Card } from '@/components/Card';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { formatRate, sourceRows, type JobSummary } from '@/features/ops-jobs/model';
import { en } from '@/i18n/en';
import { pipelineCopy } from '@/i18n/pipeline';
import { formatCount, formatDuration } from '@/lib/format';
import { cn } from '@/lib/utils';

type SourceFreshness = components['schemas']['SourceResponse'];

const copy = pipelineCopy.pipeline.sources;

interface SourcesCardProps {
  summary?: JobSummary;
  error?: unknown;
  onRetry: () => void;
  /** E-60: the age of each source's newest event (event axis). */
  freshness?: readonly SourceFreshness[];
}

/** One line per stream source: msg/s of the last bucket, data age or "No data for …", skipped and duplicates (§4). */
export function SourcesCard({ summary, error, onRetry, freshness }: SourcesCardProps) {
  const rows = summary ? sourceRows(summary) : [];
  return (
    <div id="pipeline-sources" tabIndex={-1} className="h-full outline-none">
      <Card title={copy.title}>
        {!summary && error ? (
          <ErrorState error={error} variant="block" panel={pipelineCopy.pipeline.panels.sources} onRetry={onRetry} />
        ) : !summary ? (
          <PanelSkeleton variant="list" rows={4} />
        ) : rows.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">{copy.none}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {rows.map((row) => {
              const fresh = freshness?.find((s) => s.source === row.source);
              const age = fresh?.ageSeconds === undefined ? undefined : formatDuration(fresh.ageSeconds * 1000);
              return (
                <li key={row.source} className="flex items-start justify-between gap-3 py-2.5 first:pt-0 last:pb-0">
                  <div className="min-w-0">
                    <p className="text-sm font-medium">
                      {(en.source as Record<string, string>)[row.source] ?? row.source}
                    </p>
                    <p className="text-xs text-muted-foreground tabular-nums">
                      {copy.counts(formatCount(row.skipped), formatCount(row.duplicates))}
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    <p className="text-sm font-semibold tabular-nums">
                      {row.rate === undefined ? en.kv.empty : copy.rate(formatRate(row.rate))}
                    </p>
                    {fresh ? (
                      <p
                        className={cn(
                          'text-xs tabular-nums',
                          fresh.stale ? 'text-tone-warning-fg' : 'text-muted-foreground',
                        )}
                      >
                        {fresh.stale ? copy.noData(age ?? en.kv.empty) : age ? copy.age(age) : null}
                      </p>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </div>
  );
}
