import { Callout } from '@/components/Callout';
import { en } from '@/i18n/en';
import { formatDuration } from '@/lib/format';

interface SourceStaleNoticeProps {
  source: 'GTFS_RT_VEHICLE_POSITION' | 'GTFS_RT_TRIP_UPDATE' | 'TICKETING_SALES';
  /** Age of the newest message of the source; the shell supplies it from the freshness endpoint (P5-04). */
  ageSeconds?: number;
}

/** Per-source notice, for example "No ticket sales received for 12 min" on the ticketing screen (DOC-37 §2.4). */
export function SourceStaleNotice({ source, ageSeconds }: SourceStaleNoticeProps) {
  const age = ageSeconds === undefined ? undefined : formatDuration(ageSeconds * 1000);
  const message =
    source === 'TICKETING_SALES'
      ? age
        ? en.freshness.sourceStaleTicketing(age)
        : en.freshness.sourceStaleTicketingUnknown
      : age
        ? en.freshness.sourceStaleLive(en.source[source], age)
        : en.freshness.sourceStaleLiveUnknown(en.source[source]);
  return <Callout tone="warning">{message}</Callout>;
}
