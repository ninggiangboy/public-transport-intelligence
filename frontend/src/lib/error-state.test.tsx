// Lives in lib/ because it builds real ApiErrors, and components/ may not import api/ (DOC-34 §9.1).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, type ProblemSlug } from '@/api/problem';
import { ErrorState } from '@/components/ErrorState';
import { en } from '@/i18n/en';
import { describeError } from '@/lib/problem-copy';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

function problem(slug: ProblemSlug, status: number, extra: Record<string, unknown> = {}) {
  return new ApiError(status, {
    type: `urn:pti:problem:${slug}`,
    title: `API title of ${slug}`,
    status,
    traceId: TRACE,
    detail: `API detail of ${slug}`,
    ...extra,
  });
}

// Every Problem slug of DOC-30 the generated type knows (a missing one is a compile error here) and its DOC-37 §2.3 title.
const TITLES: Record<ProblemSlug, string | undefined> = {
  'validation-error': "This request isn't valid",
  unauthorized: 'Your session has expired',
  forbidden: "You don't have access",
  'not-found': 'Page not found',
  'method-not-allowed': undefined,
  'not-acceptable': undefined,
  conflict: 'This changed while you were working',
  'dlq-invalid-state': 'This dead letter has moved on',
  'replay-already-running': 'A replay is already running',
  'job-not-restartable': "This run can't be restarted",
  'job-not-running': "This run isn't running",
  'payload-too-large': 'Payload is too large',
  'unsupported-media-type': undefined,
  'invalid-payload': "Payload doesn't match the schema",
  'pii-not-allowed': "Personal data isn't allowed",
  'business-key-changed': "The record's identity can't change",
  'replay-window-invalid': 'Check the time range',
  'unsupported-source': "This source can't be replayed",
  'analytics-recompute-unavailable': "Analytics recompute isn't available yet",
  'idempotency-key-reused': 'This request was already sent with different values',
  'job-not-allowed': "This job can't be started from here",
  'invalid-flag-value': 'Invalid value for this flag',
  'rate-limited': 'Too many requests',
  'internal-error': 'Something went wrong',
  'simulator-unavailable': "Simulator isn't responding",
  'service-unavailable': 'Service is temporarily unavailable',
};

describe('CP-07 ErrorState', () => {
  for (const [slug, title] of Object.entries(TITLES) as [ProblemSlug, string | undefined][]) {
    it(`${slug}: ${title ?? 'falls back to the title of the response'}`, () => {
      render(<ErrorState error={problem(slug, 400)} variant="inline" />);
      // Inline strips and blocks share the title; the 5xx block rewrites it to "Couldn't load", checked below.
      expect(screen.getByRole('alert')).toHaveTextContent(title ?? `API title of ${slug}`);
    });
  }

  it('is covered by en.error.slug exactly for the slugs that have copy', () => {
    const withCopy = Object.entries(TITLES)
      .filter(([, title]) => title !== undefined)
      .map(([slug]) => slug)
      .sort();
    expect(Object.keys(en.error.slug).sort()).toEqual(withCopy);
  });

  it('shows the trace id with a copy button on every API error', () => {
    render(<ErrorState error={problem('conflict', 409)} variant="block" />);
    expect(screen.getByText(TRACE)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.error.copyTrace })).toBeInTheDocument();
  });

  it('uses the title and detail of an unknown slug', () => {
    render(<ErrorState error={problem('method-not-allowed', 405)} variant="block" />);
    expect(screen.getByRole('heading', { name: 'API title of method-not-allowed' })).toBeInTheDocument();
    expect(screen.getByText('API detail of method-not-allowed')).toBeInTheDocument();
  });

  it('describes a failed fetch without a trace id', () => {
    render(<ErrorState error={new TypeError('Failed to fetch')} variant="block" panel="the alert list" />);
    expect(screen.getByRole('heading', { name: "Couldn't load the alert list" })).toBeInTheDocument();
    expect(screen.getByText("Check your connection. We'll keep trying.")).toBeInTheDocument();
    expect(screen.queryByText(en.error.traceId)).not.toBeInTheDocument();
  });

  it('describes anything else as a generic error', () => {
    expect(describeError(new Error('boom')).title).toBe('Something went wrong');
    expect(describeError('boom').title).toBe('Something went wrong');
    expect(describeError(undefined).network).toBe(false);
  });

  it('fills the sentences from the extensions of the problem', () => {
    expect(describeError(problem('dlq-invalid-state', 409, { currentStatus: 'RESOLVED' })).description).toBe(
      'Its status is now RESOLVED. The list has been refreshed.',
    );
    expect(describeError(problem('pii-not-allowed', 422, { field: 'rider_email' })).description).toBe(
      'Remove the field rider_email and save again.',
    );
    expect(describeError(problem('business-key-changed', 422, { fields: ['trip_id', 'stop_id'] })).description).toBe(
      'Keep trip_id, stop_id as they were in the original payload.',
    );
    expect(describeError(problem('rate-limited', 429, { retryAfter: 12 })).description).toBe('Try again in 12 s.');
    expect(describeError(problem('not-found', 404), { thing: 'Stop' }).title).toBe('Stop not found');
    expect(describeError(problem('replay-already-running', 409, { existingReplayId: 'rp-1' })).existingReplayId).toBe(
      'rp-1',
    );
  });

  it('lists the field errors of a validation problem', () => {
    const error = problem('validation-error', 400, { errors: [{ field: 'from', message: 'must be before to' }] });
    render(<ErrorState error={error} variant="block" />);
    expect(screen.getByText('from: must be before to')).toBeInTheDocument();
  });

  it('retries, reloads and runs the actions a slug asks for', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const signIn = vi.fn();
    const viewReplay = vi.fn();

    const { unmount } = render(<ErrorState error={problem('internal-error', 500)} variant="block" onRetry={onRetry} />);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    unmount();

    const second = render(<ErrorState error={problem('unauthorized', 401)} variant="block" actions={{ signIn }} />);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(signIn).toHaveBeenCalledTimes(1);
    second.unmount();

    render(<ErrorState error={problem('replay-already-running', 409)} variant="block" actions={{ viewReplay }} />);
    await user.click(screen.getByRole('button', { name: 'View running replay' }));
    expect(viewReplay).toHaveBeenCalledTimes(1);
  });

  it('shows no button whose handler is missing', () => {
    render(<ErrorState error={problem('unauthorized', 401)} variant="block" />);
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('CP-09 keeps stale data under an inline strip that says how old it is', () => {
    const asOf = new Date(Date.now() - 120_000).toISOString();
    render(
      <ErrorState
        error={new TypeError('offline')}
        variant="inline"
        dataAsOf={asOf}
        axis="audit"
        onRetry={() => undefined}
      />,
    );
    const strip = screen.getByRole('alert');
    expect(within(strip).getByText(/Can't reach the server\. Showing data from 2 min ago\./)).toBeInTheDocument();
    expect(within(strip).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
