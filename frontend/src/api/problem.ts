import type { components } from '@/api/generated/schema';

type Problem = components['schemas']['Problem'];
type SlugOf<T> = T extends `urn:pti:problem:${infer Slug}` ? Slug : never;

/** Slug of a Problem `type` (`not-found`, `replay-already-running`, ...), the key of its microcopy (DOC-37). */
export type ProblemSlug = SlugOf<Problem['type']>;

/**
 * Problem Details of DOC-30 §3. `about:blank` stands in when a non-2xx body is not Problem Details, for example an
 * error page of the proxy.
 */
export type ProblemDetails = Pick<Problem, 'title' | 'status' | 'traceId' | 'detail' | 'instance' | 'errors'> & {
  type: Problem['type'] | 'about:blank';
};

const URN_PREFIX = 'urn:pti:problem:';

/** A non-2xx response of the API. Network failures stay `TypeError`s from `fetch`, so they can be told apart. */
export class ApiError extends Error {
  override readonly name = 'ApiError';
  readonly status: number;
  readonly problem: ProblemDetails;
  /** `undefined` for a stand-in problem. */
  readonly slug: ProblemSlug | undefined;
  /** From the body, else from the `X-Trace-Id` header (DOC-31 §7). */
  readonly traceId: string | undefined;

  constructor(status: number, problem: ProblemDetails, headerTraceId?: string) {
    super(problem.detail ?? problem.title);
    this.status = status;
    this.problem = problem;
    this.slug = problem.type.startsWith(URN_PREFIX)
      ? (problem.type.slice(URN_PREFIX.length) as ProblemSlug)
      : undefined;
    this.traceId = problem.traceId || headerTraceId;
  }

  static from(response: Response, body: unknown): ApiError {
    const headerTraceId = response.headers.get('X-Trace-Id') ?? undefined;
    if (isProblem(body)) return new ApiError(response.status, body, headerTraceId);
    const standIn: ProblemDetails = {
      type: 'about:blank',
      title: response.statusText || `HTTP ${response.status}`,
      status: response.status,
      traceId: headerTraceId ?? '',
    };
    return new ApiError(response.status, standIn, headerTraceId);
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

function isProblem(body: unknown): body is ProblemDetails {
  if (typeof body !== 'object' || body === null) return false;
  const { type, status, title } = body as Record<string, unknown>;
  return typeof type === 'string' && typeof status === 'number' && typeof title === 'string';
}
