import { useQuery } from '@tanstack/react-query';

import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { StopDelayBar } from '@/features/scorecard/components/parts';
import { profileScale } from '@/features/scorecard/model';
import { delayProfileQuery, type ProfileParams } from '@/features/scorecard/queries';
import { scorecardCopy } from '@/i18n/scorecard';
import { formatDelaySeconds } from '@/lib/format';

const copy = scorecardCopy.scorecard;

/**
 * "Typical delay along the route" of the summary drawer (§4): E-04 for one direction and hour of the week, a bar per
 * stop with a tick at the 90th percentile. Stops without history (`NONE`) keep their row with no bar.
 */
export function DelayAlongRoute({ routeId, params }: { routeId: string; params: ProfileParams }) {
  const profile = useQuery(delayProfileQuery(routeId, params));
  if (profile.isError && !profile.data) {
    return (
      <ErrorState
        error={profile.error}
        variant="block"
        panel={copy.panels.profile}
        onRetry={() => void profile.refetch()}
      />
    );
  }
  if (!profile.data) return <PanelSkeleton variant="list" rows={4} />;
  const items = profile.data.data.items;
  if (!items.some((item) => item.avgDelaySeconds !== undefined)) {
    return <p className="py-2 text-sm text-muted-foreground">{copy.drawer.noProfile}</p>;
  }
  const max = profileScale(items);
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col gap-2">
        {items.map((item) => (
          <li
            key={`${item.stopSequence}:${item.stopId}`}
            className="grid grid-cols-[minmax(0,9rem)_minmax(0,1fr)_5.5rem] items-center gap-3 text-sm"
          >
            <span className="truncate" title={item.name}>
              {item.name}
            </span>
            {item.avgDelaySeconds === undefined ? (
              <span />
            ) : (
              <StopDelayBar label={item.name} average={item.avgDelaySeconds} p90={item.p90DelaySeconds} max={max} />
            )}
            <span className="text-right text-xs text-muted-foreground tabular-nums">
              {item.avgDelaySeconds === undefined ? '' : formatDelaySeconds(item.avgDelaySeconds)}
            </span>
          </li>
        ))}
      </ul>
      <p className="text-xs text-muted-foreground">{copy.drawer.legend}</p>
    </div>
  );
}
