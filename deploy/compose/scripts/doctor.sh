#!/usr/bin/env bash
# `make doctor` (DOC-38 §2): checks the machine before `make up`. Prints one row per check and exits 1 when any row
# is FAIL. FAIL blocks building or running the stack; WARN is a version that differs from mise.toml, or something
# that only matters later (a stack without code yet, all profiles at once, `make it`).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT" || exit 2

failures=0
row() { # <OK|WARN|FAIL> <check> <detail>
  local colour
  case "$1" in
    OK) colour=32 ;;
    WARN) colour=33 ;;
    FAIL) colour=31; failures=$((failures + 1)) ;;
  esac
  if [[ -t 1 ]]; then
    printf '\033[%sm%-4s\033[0m  %-22s %s\n' "$colour" "$1" "$2" "$3"
  else
    printf '%-4s  %-22s %s\n' "$1" "$2" "$3"
  fi
}

# The version pinned in mise.toml for a tool, e.g. `pinned node` -> 24, `pinned java` -> temurin-25.
pinned() { sed -n "s/^$1 *= *\"\(.*\)\"/\1/p" mise.toml; }

# The leading major (or major.minor with `2`) of the first version number in a string.
version_of() { # <text> [parts]
  grep -oE '[0-9]+(\.[0-9]+)*' <<<"$1" | head -1 | cut -d. -f"1-${2:-1}"
}

# A tool whose version should match mise.toml. Missing or different is FAIL when <required> is true, else WARN.
check_tool() { # <check> <command> <version command> <pinned> <parts> <required> <why>
  local name="$1" cmd="$2" version_cmd="$3" want="$4" parts="$5" required="$6" why="$7" level=WARN have
  [[ "$required" == true ]] && level=FAIL
  if ! command -v "$cmd" >/dev/null 2>&1; then
    row "$level" "$name" "not found ($why)"
    return
  fi
  have="$(version_of "$($version_cmd 2>&1)" "$parts")"
  if [[ "$want" == latest || "$have" == "$want" ]]; then
    row OK "$name" "$have"
  else
    row WARN "$name" "$have, mise.toml pins $want ($why)"
  fi
}

echo "Machine checks (DOC-38 §1-2)"
echo

# ---------------------------------------------------------------- tools

if command -v mise >/dev/null 2>&1; then
  row OK "mise" "$(version_of "$(mise --version)" 3)"
else
  row WARN "mise" "not found; tool versions below are whatever is on PATH (DOC-38 §2)"
fi

# Gradle 9 runs on Java 17+, and the foojay resolver provisions the JDK 25 toolchain itself.
java_want="$(version_of "$(pinned java)")"
if ! command -v java >/dev/null 2>&1; then
  row FAIL "java" "not found; needed to run Gradle (mise.toml pins $(pinned java))"
else
  java_have="$(version_of "$(java -version 2>&1 | head -1)")"
  if [[ "$java_have" == "$java_want" ]]; then
    row OK "java" "$java_have"
  elif (( java_have >= 17 )); then
    row WARN "java" "$java_have runs Gradle, but mise.toml pins $java_want (Gradle downloads the 25 toolchain)"
  else
    row FAIL "java" "$java_have; Gradle needs 17 or newer, mise.toml pins $java_want"
  fi
fi

has_frontend=false; [[ -f frontend/package.json ]] && has_frontend=true
has_experiments=false; [[ -f experiments/pyproject.toml ]] && has_experiments=true
check_tool node node "node --version" "$(pinned node)" 1 "$has_frontend" "frontend/"
check_tool pnpm pnpm "pnpm --version" "$(pinned pnpm)" 1 "$has_frontend" "frontend/"
check_tool uv uv "uv --version" "$(pinned uv)" 1 "$has_experiments" "experiments/"

# The Makefile and scripts run python3 for JSON and time zones (zoneinfo needs 3.9); experiments use 3.12 via uv.
python_want="$(pinned python)"
if ! command -v python3 >/dev/null 2>&1; then
  row FAIL "python3" "not found; the Makefile needs it"
else
  python_have="$(version_of "$(python3 --version 2>&1)" 2)"
  if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))'; then
    row FAIL "python3" "$python_have; the Makefile needs 3.9 or newer"
  elif [[ "$python_have" == "$python_want" ]]; then
    row OK "python3" "$python_have"
  else
    row WARN "python3" "$python_have works for the Makefile, mise.toml pins $python_want"
  fi
fi

for tool in git make openssl curl; do
  if command -v "$tool" >/dev/null 2>&1; then row OK "$tool" "found"; else row FAIL "$tool" "not found"; fi
done

# ---------------------------------------------------------------- Docker

docker_ok=false
if ! command -v docker >/dev/null 2>&1; then
  row FAIL "docker" "not found; install OrbStack or Docker Desktop"
elif ! docker info >/dev/null 2>&1; then
  row FAIL "docker" "the daemon does not answer; start OrbStack or Docker Desktop"
else
  docker_ok=true
  row OK "docker" "$(docker version --format '{{.Server.Version}}' 2>/dev/null), context $(docker context show 2>/dev/null)"
fi

if [[ "$docker_ok" == true ]]; then
  if docker compose version >/dev/null 2>&1; then
    row OK "docker compose" "$(version_of "$(docker compose version --short)" 3)"
  else
    row FAIL "docker compose" "the Compose v2 plugin is missing"
  fi

  mem_gb="$(docker info --format '{{.MemTotal}}' | awk '{printf "%.1f", $1 / 1e9}')"
  if awk -v m="$mem_gb" 'BEGIN { exit !(m >= 12) }'; then
    row OK "docker VM memory" "${mem_gb} GB"
  elif awk -v m="$mem_gb" 'BEGIN { exit !(m >= 8) }'; then
    row WARN "docker VM memory" "${mem_gb} GB: enough for core, give it 12 GB for observability and triage"
  else
    row FAIL "docker VM memory" "${mem_gb} GB; the core profile needs 8 GB (DOC-38 §1)"
  fi

  # Testcontainers looks for /var/run/docker.sock or DOCKER_HOST, which OrbStack provides neither of (S-06).
  if [[ -S /var/run/docker.sock || -n "${DOCKER_HOST:-}" ]]; then
    row OK "testcontainers" "Docker reachable for make it"
  else
    row WARN "testcontainers" "no /var/run/docker.sock and no DOCKER_HOST; make it needs them (DOC-38 §1)"
  fi
fi

# ---------------------------------------------------------------- disk and config

free_gb="$(df -Pk "$ROOT" | awk 'NR == 2 { printf "%d", $4 / 1024 / 1024 }')"
if (( free_gb >= 80 )); then
  row OK "disk free" "${free_gb} GB"
else
  row FAIL "disk free" "${free_gb} GB; the data ceiling with compose retention needs 80 GB (DOC-38 §1)"
fi

if [[ -f .env ]]; then
  row OK ".env" "present"
else
  row FAIL ".env" "missing; run make secrets"
fi

# ---------------------------------------------------------------- host ports

# The ports compose publishes for every profile, read from compose itself so the list follows compose.yaml.
if [[ "$docker_ok" == true ]]; then
  env_file=.env
  if [[ ! -f .env ]]; then
    # Before `make secrets`: fill the required secrets with placeholders, which is enough to read the port mapping.
    # Host ports and COMPOSE_* stay unset so that compose applies its defaults and the project name. The file is deleted at the end of this block.
    env_file="$(mktemp)"
    sed -E '/^(HOST_PORT|COMPOSE)_/d; s/^([A-Z0-9_]+)=$/\1=placeholder/' deploy/compose/.env.example >"$env_file"
  fi
  compose=(docker compose -f deploy/compose/compose.yaml --env-file deploy/versions.env --env-file "$env_file")
  ports="$("${compose[@]}" --profile '*' config --format json 2>/dev/null | python3 -c '
import json, sys
try:
    services = json.load(sys.stdin).get("services", {})
except ValueError:
    sys.exit()
print(" ".join(sorted({str(p["published"]) for s in services.values() for p in s.get("ports", []) if p.get("published")}, key=int)))')"
  # Ports this project already holds are fine: the stack is simply running.
  ours=" $("${compose[@]}" --profile '*' ps --format json 2>/dev/null | python3 -c '
import json, sys
print(" ".join(str(p["PublishedPort"]) for line in sys.stdin if line.strip()
               for p in (json.loads(line).get("Publishers") or []) if p.get("PublishedPort")))') "
  busy=()
  for port in $ports; do
    [[ "$ours" == *" $port "* ]] && continue
    python3 -c 'import socket, sys
s = socket.socket()
try:
    s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)' "$port" || busy+=("$port")
  done
  if [[ -z "$ports" ]]; then
    row FAIL "host ports" "could not read the published ports from compose"
  elif (( ${#busy[@]} == 0 )); then
    row OK "host ports" "$(wc -w <<<"$ports" | tr -d ' ') ports free or held by this stack"
  else
    row FAIL "host ports" "in use by another program: ${busy[*]}; change HOST_PORT_* in .env (DOC-38 §5)"
  fi
  [[ "$env_file" == .env ]] || rm -f "$env_file"
fi

echo
if (( failures > 0 )); then
  echo "$failures check(s) failed."
  exit 1
fi
echo "Ready for make up."
