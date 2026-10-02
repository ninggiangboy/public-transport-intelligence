import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { SeverityBadge } from '@/components/SeverityBadge';
import { StatusPill } from '@/components/StatusPill';
import { statusValues, type StatusDomain } from '@/components/status-map';
import { mockMatchMedia, REDUCED_MOTION_QUERY } from '@/test/media';

// DOC-35 §3.3 (tones) and DOC-37 §3.4, §3.5 (labels), written out independently of the implementation.
const TONE: Record<StatusDomain, Record<string, string>> = {
  job: {
    STARTING: 'progress',
    STARTED: 'progress',
    STOPPING: 'progress',
    COMPLETED: 'success',
    COMPLETED_WITH_SKIPS: 'warning',
    FAILED: 'danger',
    STOPPED: 'neutral',
    ABANDONED: 'neutral',
    UNKNOWN: 'neutral',
  },
  jobRequest: { PENDING: 'neutral', RUNNING: 'progress', DONE: 'success', FAILED: 'danger', REJECTED: 'danger' },
  replay: { PENDING: 'neutral', RUNNING: 'progress', DONE: 'success', FAILED: 'danger' },
  scenarioRun: { RUNNING: 'progress', COMPLETED: 'success', STOPPED: 'neutral', FAILED: 'danger' },
  dlq: {
    NEW: 'neutral',
    TRIAGING: 'progress',
    AUTO_REPLAY_SCHEDULED: 'progress',
    REPLAY_REQUESTED: 'progress',
    TRIAGED: 'info',
    PENDING_CONFIRM: 'warning',
    MANUAL: 'warning',
    REPLAYED: 'success',
    RESOLVED: 'success',
    DISCARDED: 'neutral',
  },
  feed: { STAGED: 'info', ACTIVE: 'success', RETIRED: 'neutral', REJECTED: 'danger' },
};

const LABEL: Record<StatusDomain, Record<string, string>> = {
  job: {
    STARTING: 'Starting',
    STARTED: 'Running',
    STOPPING: 'Stopping',
    COMPLETED: 'Completed',
    COMPLETED_WITH_SKIPS: 'Completed with skips',
    FAILED: 'Failed',
    STOPPED: 'Stopped',
    ABANDONED: 'Abandoned',
    UNKNOWN: 'Unknown',
  },
  jobRequest: { PENDING: 'Queued', RUNNING: 'Running', DONE: 'Done', FAILED: 'Failed', REJECTED: 'Rejected' },
  replay: { PENDING: 'Queued', RUNNING: 'Running', DONE: 'Done', FAILED: 'Failed' },
  scenarioRun: { RUNNING: 'Running', COMPLETED: 'Completed', STOPPED: 'Stopped', FAILED: 'Failed' },
  dlq: {
    NEW: 'New',
    TRIAGING: 'Triaging',
    AUTO_REPLAY_SCHEDULED: 'Auto-replay scheduled',
    REPLAY_REQUESTED: 'Replay requested',
    TRIAGED: 'Triaged',
    PENDING_CONFIRM: 'Awaiting confirmation',
    MANUAL: 'Needs manual review',
    REPLAYED: 'Replayed',
    RESOLVED: 'Resolved',
    DISCARDED: 'Discarded',
  },
  feed: { STAGED: 'Staged', ACTIVE: 'Active', RETIRED: 'Retired', REJECTED: 'Rejected' },
};

const domains = Object.keys(TONE) as StatusDomain[];

describe('DS-02 StatusPill', () => {
  for (const domain of domains) {
    it.each(Object.entries(TONE[domain]))(
      `${domain} %s has the tone %s, an icon and its DOC-37 label`,
      (status, tone) => {
        const { container } = render(<StatusPill domain={domain} status={status} />);
        expect(screen.getByText(LABEL[domain][status] ?? '')).toBeInTheDocument();
        const pill = container.firstElementChild as HTMLElement;
        expect(pill.className).toContain(`bg-tone-${tone}-bg`);
        expect(pill.className).toContain(`text-tone-${tone}-fg`);
        expect(pill.querySelector('svg')).not.toBeNull();
      },
    );
  }

  it.each(domains)('%s: an unknown status is neutral and shows the raw value', (domain) => {
    const { container } = render(<StatusPill domain={domain} status="SOMETHING_NEW" />);
    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
    expect((container.firstElementChild as HTMLElement).className).toContain('bg-tone-neutral-bg');
  });

  it('maps every value of the tables above and nothing else per domain, except the shared request table', () => {
    for (const domain of ['job', 'dlq', 'feed'] as const) {
      expect(statusValues(domain).sort()).toEqual(Object.keys(TONE[domain]).sort());
    }
    for (const domain of ['jobRequest', 'replay', 'scenarioRun'] as const) {
      expect(statusValues(domain)).toEqual(expect.arrayContaining(Object.keys(TONE[domain])));
    }
  });

  // CP-01 in spirit: every status the database allows is mapped, so none falls back to a raw value.
  describe('against the DDL (DOC-15)', () => {
    const migrations = join(process.cwd(), '../backend/db/src/main/resources/db/migration');
    const read = (file: string) => readFileSync(join(migrations, file), 'utf8');
    const checkList = (sql: string, column: string) => {
      const match = new RegExp(`${column}\\s+TEXT[^;]*?CHECK \\(${column} IN \\(([^)]*)\\)`, 's').exec(sql);
      return [...(match?.[1] ?? '').matchAll(/'([A-Z_]+)'/g)].map((m) => m[1] ?? '');
    };

    it('maps every dead letter status', () => {
      const sql = read('warehouse/V5_2__ops_tables.sql');
      const values = checkList(sql.slice(sql.indexOf('dead_letter')), 'status');
      expect(values.length).toBeGreaterThan(5);
      expect(statusValues('dlq')).toEqual(expect.arrayContaining(values));
    });

    it('maps every feed version status', () => {
      const values = checkList(read('warehouse/V2__feed_version_and_dimensions.sql'), 'status');
      expect(values).toEqual(['STAGED', 'ACTIVE', 'RETIRED', 'REJECTED']);
      expect(statusValues('feed')).toEqual(expect.arrayContaining(values));
    });

    it('maps every job request, replay request and scenario run status', () => {
      const ops = read('warehouse/V5_2__ops_tables.sql');
      const all = [...ops.matchAll(/status\s+TEXT[^;]*?CHECK \(status IN \(('PENDING'[^)]*)\)/gs)].flatMap((m) =>
        [...(m[1] ?? '').matchAll(/'([A-Z_]+)'/g)].map((v) => v[1] ?? ''),
      );
      expect(new Set(all)).toEqual(new Set(['PENDING', 'RUNNING', 'DONE', 'FAILED', 'REJECTED']));
      for (const domain of ['jobRequest', 'replay'] as const)
        expect(statusValues(domain)).toEqual(expect.arrayContaining(all));
      const sim = checkList(read('sim/V1__sim_ledger.sql'), 'status');
      expect(sim).toEqual(['RUNNING', 'COMPLETED', 'STOPPED', 'FAILED']);
      expect(statusValues('scenarioRun')).toEqual(expect.arrayContaining(sim));
    });
  });
});

describe('SeverityBadge', () => {
  it.each([
    [0, 'info', 'Informational'],
    [1, 'warning', 'Needs attention'],
    [2, 'danger', 'Urgent'],
    [null, 'neutral', 'Unclassified'],
  ] as const)('severity %s is %s with the label "%s"', (severity, tone, label) => {
    const { container } = render(<SeverityBadge severity={severity} />);
    expect(screen.getByText(label)).toBeInTheDocument();
    expect((container.firstElementChild as HTMLElement).className).toContain(`bg-tone-${tone}-bg`);
  });

  it('keeps the label as the accessible name when it is not shown', () => {
    render(<SeverityBadge severity={2} showLabel={false} />);
    expect(screen.getByRole('img', { name: 'Urgent' })).toBeInTheDocument();
    expect(screen.queryByText('Urgent')).not.toBeInTheDocument();
  });
});

describe('DS-09 reduced motion', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('turns the running icon unless the user asks for reduced motion', () => {
    mockMatchMedia({ [REDUCED_MOTION_QUERY]: false });
    const { container, unmount } = render(<StatusPill domain="job" status="STARTED" />);
    expect(container.querySelector('svg')?.getAttribute('class')).toContain('animate-spin');
    unmount();

    mockMatchMedia({ [REDUCED_MOTION_QUERY]: true });
    const reduced = render(<StatusPill domain="job" status="STARTED" />);
    const classes = (reduced.container.firstElementChild as HTMLElement).outerHTML;
    expect(classes).not.toContain('animate-');
    expect(classes).not.toContain('transition');
  });
});
