#!/usr/bin/env bash
# `make token ROLE=viewer|operator` (DOC-38 §4): prints an access token of a demo user of realm pti, obtained with the
# password grant of client pti-smoke (dev only, DOC-27 §3.1). Reads the Keycloak port and the demo passwords from
# .env (ENV_FILE overrides the path); an unset password is the user name, as in compose.yaml.
#   curl -H "Authorization: Bearer $(make -s token ROLE=operator)" http://localhost:8081/api/v1/me
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
role="${1:-}"
case "$role" in
  viewer | operator) ;;
  *) echo "usage: make token ROLE=viewer|operator" >&2; exit 2 ;;
esac

# The value of a variable in the env file, or empty.
env_value() { sed -n "s/^$1=//p" "$ENV_FILE" 2>/dev/null | tail -1; }

port="$(env_value HOST_PORT_KEYCLOAK)"
password_var="KEYCLOAK_DEMO_$(tr '[:lower:]' '[:upper:]' <<<"$role")_PASSWORD"
password="$(env_value "$password_var")"

response="$(curl -sS -X POST "http://localhost:${port:-8180}/realms/pti/protocol/openid-connect/token" \
  --data-urlencode grant_type=password --data-urlencode client_id=pti-smoke \
  --data-urlencode "username=$role" --data-urlencode "password=${password:-$role}")"
python3 -c '
import json, sys
body = json.loads(sys.argv[1])
if "access_token" not in body:
    sys.exit("Keycloak refused the password grant: " + body.get("error_description", body.get("error", "unknown error")))
print(body["access_token"])' "$response"
