import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';

import { RealtimeContext } from '@/realtime/context';
import { RealtimeController } from '@/realtime/controller';

/**
 * Owns the single event stream of the app (DOC-26 §8.1). It sits inside `QueryClientProvider`, because events write
 * to the query cache. It opens no connection until some component calls `useRealtime`.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [controller] = useState(() => new RealtimeController({ queryClient }));
  useEffect(() => controller.start(), [controller]);
  return <RealtimeContext value={controller}>{children}</RealtimeContext>;
}
