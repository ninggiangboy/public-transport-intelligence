import { useCallback, useContext, useEffect, useSyncExternalStore } from 'react';

import { RealtimeContext } from '@/realtime/context';
import type { Channel, RealtimeOptions, RealtimeState } from '@/realtime/types';

export type { Channel, RealtimeOptions, RealtimeState, RealtimeStatus } from '@/realtime/types';

const OUTSIDE_PROVIDER: RealtimeState = { status: 'connecting' };
const noSubscribe = () => () => undefined;
const outsideState = () => OUTSIDE_PROVIDER;

/**
 * Subscribes the calling component to channels of the app's single event stream (DOC-26 §8.1) and returns the state of
 * that stream. The provider merges the channels and routes of every mounted caller; events end up in the query cache,
 * so components read data with their queries, not from here. Outside a `RealtimeProvider` (a component test) it does
 * nothing and reports `connecting`.
 */
export function useRealtime(options: RealtimeOptions): RealtimeState {
  const controller = useContext(RealtimeContext);
  // Strings, so that a new array with the same content does not re-register.
  const channels = [...new Set(options.channels)].sort().join(',');
  const routeIds = [...new Set(options.routeIds)].sort().join(',');

  useEffect(
    () =>
      controller?.register({
        channels: channels ? (channels.split(',') as Channel[]) : [],
        routeIds: routeIds ? routeIds.split(',') : [],
      }),
    [controller, channels, routeIds],
  );

  return useSyncExternalStore(controller?.subscribe ?? noSubscribe, controller?.getState ?? outsideState);
}

/** For the auth provider: `reconnect()` reopens the stream with the new token after `userLoaded` (DOC-26 §8.2). */
export function useRealtimeControls(): { reconnect: () => void } {
  const controller = useContext(RealtimeContext);
  const reconnect = useCallback(() => {
    controller?.reconnect();
  }, [controller]);
  return { reconnect };
}
