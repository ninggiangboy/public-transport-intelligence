// Runtime configuration for `pnpm dev` (DOC-34 §10). The nginx image replaces this file at startup from the
// PTI_* variables of the container (frontend/nginx/40-pti-runtime-config.sh).
window.__PTI_ENV__ = {
  keycloakUrl: 'http://localhost:8180',
  keycloakRealm: 'pti',
  keycloakClientId: 'pti-web',
  mapStyle: 'online',
  demoControl: true,
  grafanaUrl: 'http://localhost:3000',
};
