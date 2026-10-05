import { ExternalLink, Lock } from 'lucide-react';

import { Duration } from '@/components/Duration';
import { ToneBadge } from '@/components/ToneBadge';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { runDurationMs, type JobRun } from '@/features/ops-jobs/model';
import { useTicker } from '@/features/ops-jobs/use-ticker';
import { pipelineCopy } from '@/i18n/pipeline';

const copy = pipelineCopy.pipeline;

/** How long a run took; a run still going counts up every second (§4). */
export function LiveDuration({ run }: { run: Pick<JobRun, 'durationMs' | 'startedAt' | 'endedAt' | 'status'> }) {
  const running = run.durationMs === undefined && run.endedAt === undefined && run.startedAt !== undefined;
  const now = useTicker(running);
  const ms = runDurationMs(run, now);
  return <Duration {...(ms === undefined ? {} : { ms })} />;
}

/** A viewer sees the runs but none of the actions (§1, AC-5). */
export function ReadOnlyBadge() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex rounded-sm">
          <ToneBadge tone="neutral" icon={Lock} label={copy.readOnly} />
        </span>
      </TooltipTrigger>
      <TooltipContent>{copy.readOnlyHelp}</TooltipContent>
    </Tooltip>
  );
}

/** "Trace" and "Logs" in Grafana Explore, in a new tab (E-32 `links`); nothing without links (§6). */
export function GrafanaLinks({ links }: { links?: { trace: string; logs: string } }) {
  if (!links) return null;
  return (
    <>
      <Button asChild variant="outline" size="sm">
        <a href={links.trace} target="_blank" rel="noreferrer noopener">
          {copy.actions.trace}
          <ExternalLink aria-hidden="true" />
        </a>
      </Button>
      <Button asChild variant="outline" size="sm">
        <a href={links.logs} target="_blank" rel="noreferrer noopener">
          {copy.actions.logs}
          <ExternalLink aria-hidden="true" />
        </a>
      </Button>
    </>
  );
}
