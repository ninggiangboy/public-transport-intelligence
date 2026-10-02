// Mapping of enum values to tone and icon (DOC-35 §3.3). Labels come from en.ts (DOC-37 §3).
import {
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CircleHelp,
  CircleSlash,
  CircleStop,
  CircleX,
  Clock,
  Hand,
  Inbox,
  Info,
  LoaderCircle,
  OctagonAlert,
  Tags,
  Trash2,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';

import type { Tone } from '@/components/tone';

export interface Visual {
  tone: Tone;
  icon: LucideIcon;
  /** The icon turns; it stands still under prefers-reduced-motion. */
  spin?: boolean;
}

export type Severity = 0 | 1 | 2 | null;

const SEVERITY: Record<'0' | '1' | '2' | 'none', Visual> = {
  '0': { tone: 'info', icon: Info },
  '1': { tone: 'warning', icon: TriangleAlert },
  '2': { tone: 'danger', icon: OctagonAlert },
  none: { tone: 'neutral', icon: CircleDashed },
};

export function severityVisual(severity: Severity): Visual {
  return severity === null ? SEVERITY.none : SEVERITY[severity === 0 ? '0' : severity === 1 ? '1' : '2'];
}

export type StatusDomain = 'job' | 'jobRequest' | 'replay' | 'dlq' | 'feed' | 'scenarioRun';

const RUNNING: Visual = { tone: 'progress', icon: LoaderCircle, spin: true };
const DONE: Visual = { tone: 'success', icon: CircleCheck };
const FAILED: Visual = { tone: 'danger', icon: CircleX };
const STOPPED: Visual = { tone: 'neutral', icon: CircleStop };

// job_request, replay_request and sim_scenario_run share one table (DOC-35 §3.3).
const REQUEST: Record<string, Visual> = {
  PENDING: { tone: 'neutral', icon: Clock },
  RUNNING,
  DONE,
  COMPLETED: DONE,
  FAILED,
  REJECTED: FAILED,
  STOPPED,
};

const VISUALS: Record<StatusDomain, Record<string, Visual>> = {
  job: {
    STARTING: RUNNING,
    STARTED: RUNNING,
    STOPPING: RUNNING,
    COMPLETED: DONE,
    COMPLETED_WITH_SKIPS: { tone: 'warning', icon: CircleAlert },
    FAILED,
    STOPPED,
    ABANDONED: { tone: 'neutral', icon: CircleSlash },
    UNKNOWN: { tone: 'neutral', icon: CircleHelp },
  },
  jobRequest: REQUEST,
  replay: REQUEST,
  scenarioRun: REQUEST,
  dlq: {
    NEW: { tone: 'neutral', icon: Inbox },
    TRIAGING: RUNNING,
    AUTO_REPLAY_SCHEDULED: RUNNING,
    REPLAY_REQUESTED: RUNNING,
    TRIAGED: { tone: 'info', icon: Tags },
    PENDING_CONFIRM: { tone: 'warning', icon: CircleHelp },
    MANUAL: { tone: 'warning', icon: Hand },
    REPLAYED: DONE,
    RESOLVED: DONE,
    DISCARDED: { tone: 'neutral', icon: Trash2 },
  },
  feed: {
    STAGED: { tone: 'info', icon: Clock },
    ACTIVE: DONE,
    RETIRED: { tone: 'neutral', icon: CircleSlash },
    REJECTED: FAILED,
  },
};

/** Tone and icon of `(domain, status)`; undefined for a value the UI does not know yet (shown as raw text). */
export function statusVisual(domain: StatusDomain, status: string): Visual | undefined {
  return Object.hasOwn(VISUALS[domain], status) ? VISUALS[domain][status] : undefined;
}

/** Every known status value per domain, for the catalogue and the tests. */
export function statusValues(domain: StatusDomain): string[] {
  return Object.keys(VISUALS[domain]);
}
