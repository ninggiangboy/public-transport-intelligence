import { createContext } from 'react';

import type { RealtimeController } from '@/realtime/controller';

/** The controller of the nearest `RealtimeProvider`; `undefined` outside one (then `useRealtime` does nothing). */
export const RealtimeContext = createContext<RealtimeController | undefined>(undefined);
