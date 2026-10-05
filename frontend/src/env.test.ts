import { describe, expect, it, vi } from 'vitest';

import { readEnv, SAFE_DEFAULTS } from '@/env';

describe('readEnv (UX-07)', () => {
  it('falls back to safe defaults when window.__PTI_ENV__ is missing, without throwing', () => {
    expect(readEnv(undefined)).toEqual({
      keycloakUrl: '',
      keycloakRealm: 'pti',
      keycloakClientId: 'pti-web',
      mapStyle: 'offline',
      demoControl: false,
      grafanaUrl: '',
    });
  });

  it('reads the values rendered by the nginx entrypoint', () => {
    const raw = {
      keycloakUrl: 'http://localhost:8180',
      keycloakRealm: 'pti',
      keycloakClientId: 'pti-web',
      mapStyle: 'online',
      demoControl: true,
      grafanaUrl: 'http://localhost:3000',
    };
    expect(readEnv(raw)).toEqual(raw);
  });

  it('accepts an empty Keycloak URL as anonymous-only (k3d lite)', () => {
    expect(readEnv({ keycloakUrl: '' }).keycloakUrl).toBe('');
  });

  it('uses the safe defaults and warns when a value is invalid', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(readEnv({ keycloakUrl: 'javascript:alert(1)', mapStyle: 'satellite' })).toEqual(SAFE_DEFAULTS);
    expect(warn).toHaveBeenCalledOnce();
  });
});
