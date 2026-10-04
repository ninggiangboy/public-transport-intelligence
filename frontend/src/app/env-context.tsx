import { createContext, useContext, type ReactNode } from 'react';

import { env, type AppEnv } from '@/env';

const EnvContext = createContext<AppEnv>(env);

/** Runtime configuration from `env.js` (DOC-34 §10). A provider only to let tests render other deployments. */
export function EnvProvider({ value, children }: { value: AppEnv; children: ReactNode }) {
  return <EnvContext value={value}>{children}</EnvContext>;
}

export function useEnv(): AppEnv {
  return useContext(EnvContext);
}
