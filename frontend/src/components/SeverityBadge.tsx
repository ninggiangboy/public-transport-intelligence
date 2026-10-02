import { ToneBadge } from '@/components/ToneBadge';
import { severityVisual, type Severity } from '@/components/status-map';
import { en } from '@/i18n/en';

interface SeverityBadgeProps {
  /** 0..2; null = not yet classified ("Unclassified"). */
  severity: Severity;
  size?: 'sm' | 'md';
  showLabel?: boolean;
}

/** Severity of an alert, dead letter or ticketing anomaly (DOC-35 §3.3, §5.1). */
export function SeverityBadge({ severity, size = 'md', showLabel = true }: SeverityBadgeProps) {
  const { tone, icon } = severityVisual(severity);
  const label = severity === null ? en.severity.unclassified : en.severity[severity];
  return <ToneBadge tone={tone} icon={icon} size={size} iconOnly={!showLabel} label={label} />;
}
