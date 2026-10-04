import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';

import { RealtimeContext } from '@/realtime/context';
import type { RealtimeController } from '@/realtime/controller';

/**
 * Owns the single event stream of the app (DOC-26 §8.1). It sits inside `QueryClientProvider`, because events write
 * to the query cache. It opens no connection until some component calls `useRealtime`. The controller (stream client,
 * cache handlers, event schemas) is loaded after the first paint, to keep it out of the initial bundle (DOC-34 §7);
 * until it arrives `useRealtime` reports `connecting`, and subscribers register as soon as it does.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [controller, setController] = useState<RealtimeController>();
  useEffect(() => {
    const effect = { active: true, stop: () => undefined as unknown };
    void import('@/realtime/controller').then(({ RealtimeController: Controller }) => {
      if (!effect.active) return;
      const created = new Controller({ queryClient });
      effect.stop = created.start();
      setController(created);
    });
    return () => {
      effect.active = false;
      effect.stop();
    };
  }, [queryClient]);
  return <RealtimeContext value={controller}>{children}</RealtimeContext>;
}
