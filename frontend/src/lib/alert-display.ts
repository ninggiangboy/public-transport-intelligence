import { Activity, Bus, Database, Inbox, Server, Ticket, TriangleAlert, type LucideIcon } from 'lucide-react';

import type { components } from '@/api/generated/schema';
import type { ToneOrAccent } from '@/components/tone';
import { alertsCopy } from '@/i18n/alerts';
import { formatDelaySeconds, formatPercentWhole } from '@/lib/format';

// How an alert reads in lists (the alert feed and the Overview's "Needs attention"; DOC-36 screens/alert-feed §4). `body` is per type (DOC-23 §10.1, and
// the Alertmanager payload for DLQ_SEVERE, FEED_STALE, INFRA); every field is read defensively, since anonymous
// callers get a reduced body (DOC-32 E-20).

export type Alert = components['schemas']['AlertResponse'];

const ICONS: Record<string, LucideIcon> = {
  DISRUPTION: TriangleAlert,
  BUNCHING: Bus,
  TICKETING_ANOMALY: Ticket,
  DLQ_SEVERE: Inbox,
  FEED_STALE: Activity,
  INFRA: Server,
};

/** The 32 px icon of a list item: the icon by type, the tone by severity; bunching has its own accent. */
export function alertVisual(alert: Pick<Alert, 'type' | 'severity'>): { icon: LucideIcon; tone: ToneOrAccent } {
  const icon = ICONS[alert.type] ?? Database;
  if (alert.type === 'BUNCHING') return { icon, tone: 'bunching' };
  return { icon, tone: alert.severity >= 2 ? 'danger' : alert.severity === 1 ? 'warning' : 'neutral' };
}

export function typeLabel(type: string): string {
  return alertsCopy.alertType[type] ?? type;
}

function num(body: Record<string, unknown>, key: string): number | undefined {
  const value = body[key];
  return typeof value === 'number' ? value : undefined;
}

function str(body: Record<string, unknown>, key: string): string | undefined {
  const value = body[key];
  return typeof value === 'string' ? value : undefined;
}

/** The Alertmanager `annotations` / `labels` of an ops alert. */
export function alertmanagerPart(alert: Alert, part: 'annotations' | 'labels'): Record<string, string> {
  const value = alert.body[part];
  if (typeof value !== 'object' || value === null) return {};
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).filter(
      (entry): entry is [string, string] => typeof entry[1] === 'string',
    ),
  );
}

/** "0:40" for a gap in seconds. */
export function formatGap(seconds: number): string {
  const whole = Math.round(seconds);
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

/** The line under the title of a list item. */
export function summaryLine(alert: Alert): string | undefined {
  const body = alert.body;
  switch (alert.type) {
    case 'DISRUPTION': {
      const delay = num(body, 'currentAvgDelaySeconds');
      const stops = Array.isArray(body.affectedStopIds) ? body.affectedStopIds.length : undefined;
      return (
        [
          delay === undefined ? undefined : formatDelaySeconds(delay),
          stops ? alertsCopy.alerts.summary.stopsAffected(stops) : undefined,
        ]
          .filter(Boolean)
          .join(' · ') || undefined
      );
    }
    case 'BUNCHING': {
      const gap = num(body, 'gapSeconds');
      return gap === undefined ? undefined : alertsCopy.alerts.summary.gap(formatGap(gap));
    }
    case 'TICKETING_ANOMALY': {
      const ratio = num(body, 'refundRatio');
      const salePoint = str(body, 'salePointId');
      return (
        [salePoint, ratio === undefined ? undefined : alertsCopy.alerts.summary.refunds(formatPercentWhole(ratio))]
          .filter(Boolean)
          .join(' · ') || undefined
      );
    }
    default:
      return alertmanagerPart(alert, 'annotations').summary;
  }
}

export interface LinkAction {
  label: string;
  href: string;
  /** A runbook outside the app: a new tab (DOC-34 §5.3, UX-09). */
  external: boolean;
}

/** The button that follows `link` (built by the API, DOC-34 §5.3); `undefined` when there is no link. */
export function linkAction(alert: Pick<Alert, 'type' | 'link'>): LinkAction | undefined {
  if (!alert.link) return undefined;
  const external = /^https?:\/\//.test(alert.link);
  const actions = alertsCopy.alerts.actions;
  const label =
    alert.type === 'DISRUPTION' || alert.type === 'BUNCHING'
      ? actions.showOnMap
      : alert.type === 'TICKETING_ANOMALY'
        ? actions.openTicketing
        : alert.type === 'DLQ_SEVERE'
          ? actions.openDeadLetters
          : external
            ? actions.openRunbook
            : actions.openPipeline;
  return { label, href: alert.link, external };
}

/** Unread for signed-in users: neither acknowledged nor resolved. */
export function isUnread(alert: Pick<Alert, 'acknowledgedAt' | 'resolvedAt'>): boolean {
  return !alert.acknowledgedAt && !alert.resolvedAt;
}
