/**
 * A fake SSE server for the realtime tests (DOC-26 §14, RT-14, RT-15). `fake.fetch` is a drop-in `fetch`: each request
 * to the stream gets a {@link FakeStream} that the test pushes frames into, closes or breaks, or a scripted refusal.
 * The frames are written the way Spring writes them (`event:name`, no space after the colon, DOC-33 §2.2).
 */

export interface Frame {
  /** The `id:` line; leave it out for `heartbeat` and `resync`, like the server. */
  id?: string;
  event?: string;
  /** Sent as the `data:` line: an object is serialised, a string goes as it is. */
  data: unknown;
}

function encode(frame: Frame): string {
  const lines: string[] = [];
  if (frame.id !== undefined) lines.push(`id:${frame.id}`);
  if (frame.event !== undefined) lines.push(`event:${frame.event}`);
  lines.push(`data:${typeof frame.data === 'string' ? frame.data : JSON.stringify(frame.data)}`);
  return `${lines.join('\n')}\n\n`;
}

/** An event frame the way DOC-33 §2.2 shows it. */
export function eventFrame(
  type: string,
  data: unknown,
  extra: { id?: string; routeId?: string; channel?: string } = {},
) {
  const id = extra.id ?? `01J8ZK3V5Q7X2M4N6P8R0T${String(++counter).padStart(4, '0')}`;
  return {
    id,
    event: type,
    data: {
      id,
      type,
      channel: extra.channel ?? 'alerts',
      occurredAt: '2026-09-29T21:19:31.020Z',
      ...(extra.routeId === undefined ? {} : { routeId: extra.routeId }),
      data,
    },
  } satisfies Frame;
}
let counter = 0;

export function heartbeatFrame(businessNow = '2026-09-29T21:19:45Z'): Frame {
  return { event: 'heartbeat', data: { type: 'heartbeat', data: { serverTime: businessNow, businessNow } } };
}

export function resyncFrame(channels: string[], reason = 'BUFFER_EXPIRED'): Frame {
  return {
    event: 'resync',
    data: { type: 'resync', occurredAt: '2026-09-29T21:25:01Z', data: { reason, channels } },
  };
}

/** One open response: push frames, end it, or break it. */
export class FakeStream {
  private controller!: ReadableStreamDefaultController<Uint8Array>;
  private readonly encoder = new TextEncoder();
  readonly body: ReadableStream<Uint8Array>;

  constructor(signal: AbortSignal | null | undefined) {
    this.body = new ReadableStream<Uint8Array>({
      start: (controller) => {
        this.controller = controller;
      },
    });
    signal?.addEventListener('abort', () => {
      try {
        this.controller.error(new DOMException('The operation was aborted.', 'AbortError'));
      } catch {
        // Already closed.
      }
    });
  }

  send(frame: Frame) {
    this.controller.enqueue(this.encoder.encode(encode(frame)));
  }

  /** The server ends the stream cleanly. */
  close() {
    this.controller.close();
  }

  /** The connection drops. */
  fail() {
    this.controller.error(new TypeError('network error'));
  }
}

export interface FakeRequest {
  url: URL;
  headers: Headers;
  /** `Date.now()` when the request came in (fake timers make it exact). */
  at: number;
  /** Set when the signal of the request aborted. */
  aborted: boolean;
  stream?: FakeStream;
}

type Script = { status: number; headers?: Record<string, string> } | { error: Error };

export class FakeSseServer {
  readonly requests: FakeRequest[] = [];
  private readonly script: Script[] = [];

  /** The next request is refused with this status (Problem Details body, like the api). */
  refuseNext(status: number, headers: Record<string, string> = {}) {
    this.script.push({ status, headers });
  }

  /** The next request fails before any response, like a network error. */
  failNext() {
    this.script.push({ error: new TypeError('Failed to fetch') });
  }

  get last(): FakeRequest {
    const request = this.requests.at(-1);
    if (!request) throw new Error('The stream was never requested');
    return request;
  }

  /** The stream of the latest request. */
  get stream(): FakeStream {
    const { stream } = this.last;
    if (!stream) throw new Error('The latest request was refused');
    return stream;
  }

  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    const request: FakeRequest = { url, headers: new Headers(init?.headers), at: Date.now(), aborted: false };
    init?.signal?.addEventListener('abort', () => {
      request.aborted = true;
    });
    this.requests.push(request);
    const scripted = this.script.shift();
    if (scripted && 'error' in scripted) return Promise.reject(scripted.error);
    if (scripted) {
      return Promise.resolve(
        Response.json(
          { type: 'urn:pti:problem:refused', title: 'refused', status: scripted.status },
          { status: scripted.status, headers: { 'Content-Type': 'application/problem+json', ...scripted.headers } },
        ),
      );
    }
    request.stream = new FakeStream(init?.signal);
    return Promise.resolve(
      new Response(request.stream.body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
    );
  };
}
