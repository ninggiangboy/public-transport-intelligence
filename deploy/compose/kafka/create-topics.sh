#!/usr/bin/env bash
# Creates the topics listed in topics.yaml and applies their config (DOC-39 §3.3). Idempotent: existing topics keep
# their data, their config is re-applied, and a partition count lower than the current one only prints a warning.
set -euo pipefail

TOPICS_FILE="${1:-/topics.yaml}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
RF="${TOPIC_REPLICATION_FACTOR:-1}"
BIN=/opt/kafka/bin

# Emits one line per topic: "<name> <partitions> <k=v,k=v,...>".
parse() {
  awk '
    function flush() { if (name != "") print name, parts, (cfg == "" ? "-" : cfg); name = ""; parts = ""; cfg = "" }
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    /^  - name:/ { flush(); name = $3; next }
    /^    partitions:/ { parts = $2; next }
    /^    config:/ { next }
    /^      [^ ]+:/ { key = $1; sub(/:$/, "", key); val = $2; gsub(/"/, "", val); cfg = cfg (cfg == "" ? "" : ",") key "=" val; next }
    END { flush() }
  ' "$1"
}

existing="$($BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list)"

parse "$TOPICS_FILE" | while read -r name partitions config; do
  if ! grep -qxF -- "$name" <<<"$existing"; then
    args=(--create --if-not-exists --topic "$name" --partitions "$partitions" --replication-factor "$RF")
    [[ "$config" != "-" ]] && IFS=',' read -ra kv <<<"$config" && for c in "${kv[@]}"; do args+=(--config "$c"); done
    $BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" "${args[@]}"
    continue
  fi

  current="$($BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --describe --topic "$name" \
    | awk -F'PartitionCount: ' 'NF > 1 { split($2, a, /[[:space:]]/); print a[1]; exit }')"
  if (( partitions > current )); then
    $BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --alter --topic "$name" --partitions "$partitions"
    echo "Increased partitions of $name from $current to $partitions"
  elif (( partitions < current )); then
    echo "WARNING: $name has $current partitions, topics.yaml asks for $partitions; partitions cannot be reduced" >&2
  fi
  if [[ "$config" != "-" ]]; then
    $BIN/kafka-configs.sh --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name "$name" \
      --add-config "$config" >/dev/null
  fi
  echo "Topic $name is up to date"
done
