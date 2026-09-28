#!/bin/sh
# Registers every connector in /connectors (DOC-39 §3.4). Each file is {"name": "...", "config": {...}}.
# PUT /connectors/<name>/config creates or updates, so the script is idempotent. It then waits until the
# connector and all its tasks are RUNNING, and exits 1 when one of them FAILED or the wait times out.
# Runs in the pinned curl image (busybox sh and awk, no jq).
set -eu

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
TIMEOUT_SECONDS="${REGISTER_TIMEOUT_SECONDS:-120}"

# json_member <name|config> <file>: prints the top-level "name" string or the "config" object verbatim.
json_member() {
  awk -v want="$1" 'BEGIN { RS = "\001" } {
    s = $0; n = length(s); depth = 0; instr = 0; esc = 0; key = ""; last = ""; value = 0; start = 0
    for (i = 1; i <= n; i++) {
      c = substr(s, i, 1)
      if (instr) {
        if (esc) { esc = 0; tok = tok c }
        else if (c == "\\") { esc = 1; tok = tok c }
        else if (c == "\"") {
          instr = 0
          if (depth == 1) {
            if (value) { if (key == "name" && want == "name") { print tok; exit 0 } value = 0 }
            else last = tok
          }
        } else tok = tok c
        continue
      }
      if (c == "\"") { instr = 1; tok = ""; continue }
      if (depth == 1 && c == ":") { key = last; value = 1; continue }
      if (depth == 1 && c == ",") { value = 0; continue }
      if (c == "{") {
        depth++
        if (depth == 2 && value && key == "config") start = i
        continue
      }
      if (c == "}") {
        if (depth == 2 && start && want == "config") { print substr(s, start, i - start + 1); exit 0 }
        if (depth == 2) value = 0
        depth--
      }
    }
    exit 1
  }' "$2"
}

register() {
  file="$1"
  name="$(json_member name "$file")" || { echo "$file: no top-level \"name\"" >&2; return 1; }
  config="$(json_member config "$file")" || { echo "$file: no \"config\" object" >&2; return 1; }

  code="$(curl -sS -o /tmp/put-response -w '%{http_code}' -X PUT -H 'Content-Type: application/json' \
    --data "$config" "$CONNECT_URL/connectors/$name/config")"
  case "$code" in
    200) echo "$name: config updated" ;;
    201) echo "$name: created" ;;
    *) echo "$name: PUT config returned HTTP $code" >&2; cat /tmp/put-response >&2; echo >&2; return 1 ;;
  esac

  elapsed=0
  while :; do
    status="$(curl -sS "$CONNECT_URL/connectors/$name/status" || true)"
    states="$(printf '%s' "$status" | grep -o '"state":"[A-Z_]*"' | cut -d'"' -f4 || true)"
    if printf '%s\n' "$states" | grep -qx FAILED; then
      echo "$name: FAILED" >&2
      printf '%s\n' "$status" >&2
      return 1
    fi
    # The connector state plus at least one task, all RUNNING.
    total="$(printf '%s\n' "$states" | grep -c . || true)"
    running="$(printf '%s\n' "$states" | grep -cx RUNNING || true)"
    if [ "$total" -ge 2 ] && [ "$running" -eq "$total" ]; then
      echo "$name: connector and $((total - 1)) task(s) RUNNING"
      return 0
    fi
    if [ "$elapsed" -ge "$TIMEOUT_SECONDS" ]; then
      echo "$name: not RUNNING after ${TIMEOUT_SECONDS}s" >&2
      printf '%s\n' "$status" >&2
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

found=0
for file in /connectors/*.json; do
  [ -e "$file" ] || continue
  found=1
  register "$file"
done
[ "$found" -eq 1 ] || { echo "no connector files in /connectors" >&2; exit 1; }
