import { ToneBadge } from '@/components/ToneBadge';
import { statusVisual, type StatusDomain } from '@/components/status-map';
import { en } from '@/i18n/en';
import { useReducedMotion } from '@/lib/use-reduced-motion';

interface StatusPillProps {
  domain: StatusDomain;
  status: string;
  size?: 'sm' | 'md';
}

/** Status of a job, request, replay, dead letter, feed or scenario run. Unknown status: neutral with the raw value. */
export function StatusPill({ domain, status, size = 'md' }: StatusPillProps) {
  const reducedMotion = useReducedMotion();
  const visual = statusVisual(domain, status);
  const labels: Record<string, string> = en.status[domain];
  const label = Object.hasOwn(labels, status) ? (labels[status] ?? status) : status;
  return (
    <ToneBadge
      tone={visual?.tone ?? 'neutral'}
      {...(visual ? { icon: visual.icon } : {})}
      spin={Boolean(visual?.spin) && !reducedMotion}
      size={size}
      label={label}
    />
  );
}
