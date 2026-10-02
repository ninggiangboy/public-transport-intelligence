#!/bin/sh
# Renders the runtime configuration of the pti-frontend image (DOC-34 §10, DOC-29 §3.5, DOC-27 §5.3) before nginx
# starts: /env.js for the app and the CSP of the server. Run by the nginx image's /docker-entrypoint.sh.
set -eu

html=/usr/share/nginx/html
keycloak_url="${PTI_KEYCLOAK_URL:-}"
tile_origins="${PTI_MAP_TILE_ORIGINS:-}"

case "${PTI_MAP_STYLE:-offline}" in
  offline | online) map_style="${PTI_MAP_STYLE:-offline}" ;;
  *)
    echo "$0: PTI_MAP_STYLE must be offline or online, got '${PTI_MAP_STYLE}'; using offline" >&2
    map_style=offline
    ;;
esac

case ",${PTI_EXTRA_PROFILES:-}," in
  *,demo,*) demo_control=true ;;
  *) demo_control=false ;;
esac

# JSON string literal: backslashes and double quotes escaped; the values never hold control characters.
json() {
  printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
}

cat >"$html/env.js" <<JS
window.__PTI_ENV__ = {
  keycloakUrl: $(json "$keycloak_url"),
  keycloakRealm: $(json "${PTI_KEYCLOAK_REALM:-pti}"),
  keycloakClientId: $(json "${PTI_KEYCLOAK_CLIENT_ID:-pti-web}"),
  mapStyle: $(json "$map_style"),
  demoControl: $demo_control
};
JS

# Origins only end up in the CSP; anything with a quote or a semicolon would break out of the header.
for origin in $keycloak_url $tile_origins; do
  case "$origin" in
    *[\"\;\']*) echo "$0: refusing origin '$origin' in the CSP" >&2; exit 1 ;;
  esac
done

frame_src="${keycloak_url:-'none'}"
PTI_CSP="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: $tile_origins; font-src 'self'; worker-src 'self'; connect-src 'self' $keycloak_url $tile_origins; frame-src $frame_src; frame-ancestors 'none'; base-uri 'self'; form-action 'self' $keycloak_url; object-src 'none'"
# Collapse the gaps left by empty origins.
PTI_CSP="$(printf '%s' "$PTI_CSP" | tr -s ' ' | sed 's/ ;/;/g')"
PTI_API_UPSTREAM="${PTI_API_UPSTREAM:-http://api:8080}"
# 15-local-resolvers.envsh exports it when NGINX_ENTRYPOINT_LOCAL_RESOLVERS is set (Dockerfile).
NGINX_LOCAL_RESOLVERS="${NGINX_LOCAL_RESOLVERS:-127.0.0.11}"
export PTI_CSP PTI_API_UPSTREAM NGINX_LOCAL_RESOLVERS

# shellcheck disable=SC2016 # the variable names are for envsubst, not for the shell
envsubst '${PTI_CSP} ${PTI_API_UPSTREAM} ${NGINX_LOCAL_RESOLVERS}' \
  </etc/nginx/pti/default.conf.template >/etc/nginx/conf.d/default.conf

echo "$0: env.js rendered (mapStyle=$map_style, demoControl=$demo_control, keycloak=${keycloak_url:-none})"
