import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RealtimeProvider } from '@/realtime/RealtimeProvider';
import { useRealtime, useRealtimeControls } from '@/realtime/useRealtime';
import type { RealtimeOptions } from '@/realtime/types';
import { FakeSseServer, heartbeatFrame } from '@/test/sse';

let fake: FakeSseServer;
let client: QueryClient;

async function elapse(ms: number) {
  // The provider loads the controller with a dynamic import: let it arrive, and the subscribers register with it,
  // before the clock moves.
  await act(async () => {
    await import('@/realtime/controller');
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

function Probe({ name, ...options }: RealtimeOptions & { name: string }) {
  const state = useRealtime(options);
  return (
    <p data-testid={name}>
      {state.status}|{state.businessNow ?? '-'}
    </p>
  );
}

function Reconnect() {
  const { reconnect } = useRealtimeControls();
  return (
    <button
      type="button"
      onClick={() => {
        reconnect();
      }}
    >
      go
    </button>
  );
}

function app(children: React.ReactNode) {
  return (
    <QueryClientProvider client={client}>
      <RealtimeProvider>{children}</RealtimeProvider>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.useFakeTimers();
  fake = new FakeSseServer();
  client = new QueryClient();
  // The stream goes to the fake server; every other request (the API) keeps going to MSW.
  const original = globalThis.fetch;
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) =>
    (input instanceof Request ? input.url : input.toString()).includes('/api/v1/stream')
      ? fake.fetch(input, init)
      : original(input, init),
  );
});

afterEach(() => {
  vi.useRealTimers();
});

describe('RealtimeProvider and useRealtime', () => {
  it('opens no connection when no component subscribes', async () => {
    render(app(<p>nothing here</p>));
    await elapse(60_000);
    expect(fake.requests).toHaveLength(0);
  });

  it('connects for the mounted subscribers, merges them, and closes when they leave', async () => {
    const view = render(
      app(
        <>
          <Probe name="map" channels={['vehicles']} routeIds={['18']} />
          <Probe name="alerts" channels={['alerts']} routeIds={['2']} />
        </>,
      ),
    );
    expect(screen.getByTestId('map')).toHaveTextContent('connecting|-');
    await elapse(500);

    expect(fake.requests).toHaveLength(1);
    expect(fake.last.url.searchParams.get('channels')).toBe('vehicles,alerts');
    expect(fake.last.url.searchParams.getAll('routeId')).toEqual(['18', '2']);

    await elapse(0);
    await act(() => {
      fake.stream.send(heartbeatFrame('2026-09-29T21:19:45Z'));
      return vi.advanceTimersByTimeAsync(0);
    });
    // Every caller sees the one connection.
    expect(screen.getByTestId('map')).toHaveTextContent('open|2026-09-29T21:19:45Z');
    expect(screen.getByTestId('alerts')).toHaveTextContent('open|2026-09-29T21:19:45Z');

    view.rerender(app(<Probe name="map" channels={['vehicles']} routeIds={['18']} />));
    await elapse(0);
    view.unmount();
    await elapse(500);
    expect(fake.requests[0]?.aborted).toBe(true);
  });

  it('does not reconnect when a caller re-renders with the same channels in another order', async () => {
    const tree = (channels: RealtimeOptions['channels']) => app(<Probe name="p" channels={channels} />);
    const view = render(tree(['alerts', 'vehicles']));
    await elapse(500);
    view.rerender(tree(['vehicles', 'alerts']));
    await elapse(1_000);
    expect(fake.requests).toHaveLength(1);
  });

  it('exposes reconnect() for the auth provider', async () => {
    render(
      app(
        <>
          <Probe name="p" channels={['alerts']} />
          <Reconnect />
        </>,
      ),
    );
    await elapse(500);
    act(() => {
      screen.getByRole('button', { name: 'go' }).click();
    });
    await elapse(0);
    expect(fake.requests).toHaveLength(2);
  });

  it('does nothing outside a provider', async () => {
    render(
      <>
        <Probe name="p" channels={['alerts']} />
        <Reconnect />
      </>,
    );
    act(() => {
      screen.getByRole('button', { name: 'go' }).click();
    });
    await elapse(10_000);
    expect(screen.getByTestId('p')).toHaveTextContent('connecting|-');
    expect(fake.requests).toHaveLength(0);
  });
});
