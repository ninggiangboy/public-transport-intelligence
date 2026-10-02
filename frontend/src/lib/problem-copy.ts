// Turns any thrown value into the copy of DOC-37 §2.3. It duck-types the ApiError of src/api/problem.ts, because
// components/ must not import api/ (DOC-34 §9.1); en.error.slug is checked against the generated ProblemSlug type in a
// test.
import { type ErrorCopyParams } from '@/i18n/design-system';
import { en } from '@/i18n/en';

export type ErrorAction = 'retry' | 'reload' | 'signIn' | 'goBack' | 'viewReplay';

export interface DescribedError {
  title: string;
  description?: string;
  /** Per-field messages of `errors[]`, listed under the description. */
  fieldErrors: { field: string; message: string }[];
  /** Problem Details `traceId`; network failures have none. */
  traceId?: string;
  /** Problem slug when the API sent one. */
  slug?: string;
  /** The button the copy asks for besides "Retry". */
  action?: ErrorAction;
  /** Value of the `existingReplayId` extension of `replay-already-running`. */
  existingReplayId?: string;
  /** True for a failed fetch: no response at all. */
  network: boolean;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : undefined;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}

function fieldErrorsOf(problem: Record<string, unknown> | undefined) {
  const list = problem?.errors;
  if (!Array.isArray(list)) return [];
  return list.flatMap((item: unknown) => {
    const entry = asRecord(item);
    const message = text(entry?.message);
    return message ? [{ field: text(entry?.field) ?? '', message }] : [];
  });
}

function hasSlug(slug: string): slug is keyof typeof en.error.slug {
  return Object.hasOwn(en.error.slug, slug);
}

/**
 * Title, description, trace id and action for an error. Slugs of DOC-37 §2.3 get their own copy; any other Problem
 * Details response shows its own `title` and `detail`; a failed fetch is the network copy; anything else the generic one.
 */
export function describeError(error: unknown, options: { thing?: string } = {}): DescribedError {
  const record = asRecord(error);
  const problem = asRecord(record?.problem);
  const slug = text(record?.slug);
  const traceId = text(record?.traceId);

  if (error instanceof TypeError) {
    return { ...en.error.network, fieldErrors: [], network: true };
  }

  const params: ErrorCopyParams = {
    ...(text(problem?.detail) ? { detail: text(problem?.detail) } : {}),
    ...(text(problem?.currentStatus) ? { currentStatus: text(problem?.currentStatus) } : {}),
    ...(text(problem?.field) ? { field: text(problem?.field) } : {}),
    ...(Array.isArray(problem?.fields)
      ? { fields: problem.fields.join(', ') }
      : text(problem?.fields)
        ? { fields: text(problem?.fields) }
        : {}),
    ...(typeof problem?.retryAfter === 'number' ? { seconds: problem.retryAfter } : {}),
    ...(options.thing ? { thing: options.thing } : {}),
  };

  const base = {
    fieldErrors: fieldErrorsOf(problem),
    ...(traceId ? { traceId } : {}),
    ...(slug ? { slug } : {}),
    ...(text(problem?.existingReplayId) ? { existingReplayId: text(problem?.existingReplayId) } : {}),
    network: false,
  };

  if (slug && hasSlug(slug)) {
    const copy: {
      title: (p: ErrorCopyParams) => string;
      description?: (p: ErrorCopyParams) => string | undefined;
      action?: ErrorAction;
    } = en.error.slug[slug];
    const description = copy.description?.(params);
    return {
      ...base,
      title: copy.title(params),
      ...(description ? { description } : {}),
      ...(copy.action ? { action: copy.action } : {}),
    };
  }

  // A slug without copy (method-not-allowed ...) or a stand-in problem: the API's own words.
  const title = text(problem?.title);
  if (title) {
    return { ...base, title, ...(params.detail ? { description: params.detail } : {}) };
  }
  return { ...base, ...en.error.generic, action: 'retry' };
}
