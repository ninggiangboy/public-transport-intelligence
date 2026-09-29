#!/usr/bin/env bash
# `make clock-offset AT=<HH:MM|now>` (DOC-38 §3.1, DR-67): writes PTI_CLOCK_OFFSET to .env so that business time is
# HH:MM in Chicago right now. The offset is whole minutes and the shortest shift that gets there (within ±12h, well
# inside the ±24h BusinessClock accepts). Containers pick it up on the next `make up`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ENV_FILE="$ROOT/.env"
AT="${1:-}"

if [[ ! "$AT" =~ ^(now|([01][0-9]|2[0-3]):[0-5][0-9])$ ]]; then
  echo "usage: make clock-offset AT=<HH:MM|now>, e.g. AT=16:30 for the Chicago evening peak" >&2
  exit 2
fi

offset="$(python3 - "$AT" <<'EOF'
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

at = sys.argv[1]
if at == "now":
    print("0s")
    sys.exit()
now = datetime.now(ZoneInfo("America/Chicago")).replace(second=0, microsecond=0)
hour, minute = map(int, at.split(":"))
minutes = round((now.replace(hour=hour, minute=minute) - now) / timedelta(minutes=1))
# Shortest shift: -719..720 minutes.
minutes = (minutes + 719) % 1440 - 719
print(f"{minutes}m")
EOF
)"

if grep -q '^PTI_CLOCK_OFFSET=' "$ENV_FILE"; then
  tmp="$(mktemp)"
  awk -v v="$offset" 'BEGIN { FS = OFS = "=" } $1 == "PTI_CLOCK_OFFSET" { print "PTI_CLOCK_OFFSET=" v; next } { print }' \
    "$ENV_FILE" >"$tmp"
  cat "$tmp" >"$ENV_FILE" && rm -f "$tmp"
else
  printf 'PTI_CLOCK_OFFSET=%s\n' "$offset" >>"$ENV_FILE"
fi

echo "PTI_CLOCK_OFFSET=$offset (Chicago now: $(TZ=America/Chicago date +%H:%M), business time: $AT)."
echo "Run 'make up' to recreate the app containers with the new offset."
