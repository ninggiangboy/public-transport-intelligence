#!/usr/bin/env bash
# `make secrets` (DOC-38 §4.1, DOC-39 §5): creates .env from .env.example, fills every empty variable marked
# `# generate: <kind>`, then renders deploy/compose/.generated/ from .env. Never overwrites a value that is set.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
EXAMPLE="$ROOT/deploy/compose/.env.example"
ENV_FILE="$ROOT/.env"
GENERATED="$ROOT/deploy/compose/.generated"

# Alphanumeric only, so values need no escaping in JDBC URLs, YAML or JSON.
random_alnum() { # <length> [charset]
  local length="$1" charset="${2:-A-Za-z0-9}" out=""
  while (( ${#out} < length )); do
    out+="$(openssl rand -base64 48 | LC_ALL=C tr -dc "$charset")"
  done
  printf '%s' "${out:0:length}"
}

generate() { # <kind>
  case "$1" in
    password) random_alnum 24 ;;
    s3-access-key) random_alnum 20 'A-Z0-9' ;;
    s3-secret-key) random_alnum 40 ;;
    # A Kafka cluster id is a base64url-encoded UUID without padding (kafka-storage.sh random-uuid).
    kafka-cluster-id) openssl rand 16 | openssl base64 | tr '+/' '-_' | tr -d '=\n' ;;
    *) echo "Unknown generator '$1' in .env.example" >&2; exit 1 ;;
  esac
}

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$EXAMPLE" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "Created .env from deploy/compose/.env.example"
fi

# Add variables that exist in .env.example but not yet in .env (the example grows phase by phase).
while IFS= read -r line; do
  if [[ "$line" =~ ^([A-Z0-9_]+)= ]] && ! grep -q "^${BASH_REMATCH[1]}=" "$ENV_FILE"; then
    printf '%s\n' "$line" >>"$ENV_FILE"
    echo "Added ${BASH_REMATCH[1]} to .env"
  fi
done <"$EXAMPLE"

# Fill empty variables that the example marks as generated.
kind=""
filled=0
while IFS= read -r line; do
  if [[ "$line" =~ ^#\ generate:\ ([a-z0-9-]+) ]]; then
    kind="${BASH_REMATCH[1]}"
    continue
  fi
  if [[ -n "$kind" && "$line" =~ ^([A-Z0-9_]+)= ]]; then
    name="${BASH_REMATCH[1]}"
    if grep -q "^${name}=$" "$ENV_FILE"; then
      value="$(generate "$kind")"
      tmp="$(mktemp)"
      awk -v n="$name" -v v="$value" 'BEGIN { FS = OFS = "=" } $1 == n && $2 == "" { print n "=" v; next } { print }' \
        "$ENV_FILE" >"$tmp"
      cat "$tmp" >"$ENV_FILE" && rm -f "$tmp"
      filled=$((filled + 1))
    fi
  fi
  kind=""
done <"$EXAMPLE"
echo "Generated $filled value(s) in .env"

# Render files derived from .env.
mkdir -p "$GENERATED"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

s3_json="$(<"$ROOT/deploy/compose/seaweedfs/s3.json.tmpl")"
for var in S3_ADMIN_ACCESS_KEY S3_ADMIN_SECRET_KEY S3_CONNECT_ACCESS_KEY S3_CONNECT_SECRET_KEY S3_ETL_ACCESS_KEY \
  S3_ETL_SECRET_KEY; do
  s3_json="${s3_json//\$\{$var\}/${!var}}"
done
printf '%s\n' "$s3_json" >"$GENERATED/s3.json"
printf '%s' "$ALERTMANAGER_WEBHOOK_TOKEN" >"$GENERATED/webhook-token"
# SeaweedFS, Alertmanager and api run as non-root users and must be able to read the files mounted into them; the
# directory is git-ignored and only on the developer's machine.
chmod 644 "$GENERATED/webhook-token" "$GENERATED/s3.json"
echo "Rendered deploy/compose/.generated/"
