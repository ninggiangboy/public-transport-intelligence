import { LINK_TONE, linkStatus, pollSeconds, type LinkStatus } from '@/app/shell/link-status';
import { toneClasses, type Tone } from '@/components/tone';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { en } from '@/i18n/en';
import { useOnline } from '@/lib/browser';
import { useRelative } from '@/lib/use-now';
import { cn } from '@/lib/utils';
import type { RealtimeState } from '@/realtime/useRealtime';

/** 7 px dot with a 3 px halo (DOC-35 §5.2). */
export function StatusDot({ tone, className }: { tone: Tone; className?: string }) {
  const halo: Record<Tone, string> = {
    neutral: 'ring-tone-neutral-bg',
    info: 'ring-tone-info-bg',
    success: 'ring-tone-success-bg',
    teal: 'ring-tone-teal-bg',
    warning: 'ring-tone-warning-bg',
    danger: 'ring-tone-danger-bg',
    progress: 'ring-tone-progress-bg',
  };
  return (
    <span
      aria-hidden="true"
      className={cn('size-[7px] shrink-0 rounded-full ring-[3px]', toneClasses(tone).solid, halo[tone], className)}
    />
  );
}

function Detail({ status, state }: { status: LinkStatus; state: RealtimeState }) {
  const last = state.lastEventAt?.toISOString();
  const relative = useRelative(last ?? new Date(0).toISOString(), 'audit');
  if (status === 'polling') return <>{en.realtime.every(pollSeconds(state))}</>;
  if (!last || status === 'reconnecting') return null;
  return <>{status === 'offline' ? en.realtime.lastUpdate(relative) : en.realtime.updated(relative)}</>;
}

interface RealtimeStatusDotProps {
  state: RealtimeState;
  /** `compact`: the dot and the label only (mobile top bar). */
  compact?: boolean;
}

/** State of the event stream: "Live", "Reconnecting…", "Polling", "Offline", with a tooltip (DOC-37 §2.4). */
export function RealtimeStatusDot({ state, compact = false }: RealtimeStatusDotProps) {
  const online = useOnline();
  const status = linkStatus(state, online);
  const help = status === 'polling' ? en.realtime.help.polling(pollSeconds(state)) : en.realtime.help[status];
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span
          role="status"
          tabIndex={0}
          data-status={status}
          className="inline-flex items-center gap-2 rounded-sm text-xs whitespace-nowrap text-muted-foreground tabular-nums"
        >
          <StatusDot tone={LINK_TONE[status]} />
          <span className="font-medium text-foreground-2">{en.realtime[status]}</span>
          {compact ? null : (
            <span>
              <Detail status={status} state={state} />
            </span>
          )}
        </span>
      </TooltipTrigger>
      <TooltipContent>{help}</TooltipContent>
    </Tooltip>
  );
}
