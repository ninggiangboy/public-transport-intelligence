import { Info, TriangleAlert, X } from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';

import { useFreshness } from '@/app/freshness';
import { bannerCondition, RECONNECTING_BANNER_AFTER_MS, type BannerCondition } from '@/app/shell/stale-banner';
import { toneClasses } from '@/components/tone';
import { Button } from '@/components/ui/button';
import { en } from '@/i18n/en';
import { useOnline, useStoredFlag } from '@/lib/browser';
import { formatDuration } from '@/lib/format';
import { useRelative } from '@/lib/use-now';
import { cn } from '@/lib/utils';
import type { RealtimeState } from '@/realtime/useRealtime';

function Strip({ tone, children, action }: { tone: 'warning' | 'info'; children: ReactNode; action?: ReactNode }) {
  const Icon = tone === 'warning' ? TriangleAlert : Info;
  return (
    <div className={cn('flex items-center gap-2.5 rounded-lg border px-3 py-2 text-sm', toneClasses(tone).surface)}>
      <Icon className="size-4 shrink-0" strokeWidth={1.75} aria-hidden="true" />
      <p className="min-w-0 flex-1">{children}</p>
      {action}
    </div>
  );
}

function OfflineText({ at }: { at: number }) {
  return <>{en.banner.offline(useRelative(new Date(at).toISOString(), 'audit'))}</>;
}

function message(condition: BannerCondition): ReactNode {
  switch (condition.kind) {
    case 'offline':
      return condition.lastDataAt === undefined ? en.banner.offlineNoData : <OfflineText at={condition.lastDataAt} />;
    case 'freshnessUnknown':
      return en.banner.freshnessUnknown;
    case 'stale':
      return en.banner.stale(en.source[condition.source], formatDuration(condition.ageSeconds * 1000));
    case 'staleNoData':
      return en.banner.staleNoData;
    case 'polling':
      return en.banner.polling(condition.seconds);
    case 'reconnecting':
      return en.banner.reconnecting;
  }
}

/** True once `status` has stayed `reconnecting` for 5 s. */
function useReconnectingLong(status: RealtimeState['status']): boolean {
  // A new token per status change, so that a timer of an earlier reconnecting spell does not count.
  const spell = useMemo(() => ({ status }), [status]);
  const [longSpell, setLongSpell] = useState<object>();
  useEffect(() => {
    if (spell.status !== 'reconnecting') return;
    const timer = setTimeout(() => {
      setLongSpell(spell);
    }, RECONNECTING_BANNER_AFTER_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [spell]);
  return longSpell === spell;
}

/**
 * One strip at the top of the content, above the PageHeader, showing only the most important condition: offline,
 * freshness unknown, stale live data, polling, reconnecting (DOC-37 §2.4). No close button; it goes when the condition
 * does.
 */
export function StaleBanner({ realtime, className }: { realtime: RealtimeState; className?: string }) {
  const online = useOnline();
  const freshness = useFreshness();
  const reconnectingLong = useReconnectingLong(realtime.status);
  const condition = bannerCondition({ online, freshness, realtime, reconnectingLong });
  return (
    <div role="status" data-testid="stale-banner" className={condition ? className : undefined}>
      {condition ? (
        <Strip tone={condition.kind === 'polling' || condition.kind === 'reconnecting' ? 'info' : 'warning'}>
          {message(condition)}
        </Strip>
      ) : null}
    </div>
  );
}

const OPS_NARROW_DISMISSED = 'pti.opsNarrowNotice.dismissed';

/** Ops pages under 1280 px (DOC-34 §6); dismissed for the rest of the browser session. */
export function OpsNarrowNotice({ className }: { className?: string }) {
  const [dismissed, setDismissed] = useStoredFlag('session', OPS_NARROW_DISMISSED, false);
  if (dismissed) return null;
  return (
    <div className={className}>
      <Strip
        tone="info"
        action={
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={en.banner.dismiss}
            onClick={() => {
              setDismissed(true);
            }}
          >
            <X aria-hidden="true" />
          </Button>
        }
      >
        {en.banner.opsNarrow}
      </Strip>
    </div>
  );
}
