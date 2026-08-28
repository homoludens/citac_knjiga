#!/usr/bin/env bash
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MODEL_PACKAGE=${MODEL_PACKAGE:-}
OUTPUT_DIR=${OUTPUT_DIR:-/tmp/citac-knjiga-task-5-2}
WORKLOAD_SECONDS=${WORKLOAD_SECONDS:-60}
RUNTIME_CONFIGS=${RUNTIME_CONFIGS:-cpu:1,cpu:2,cpu:4,xnnpack:1,xnnpack:2,xnnpack:4}

if [[ -z "$MODEL_PACKAGE" || ! -f "$MODEL_PACKAGE" ]]; then
  printf '%s\n' "Set MODEL_PACKAGE to the locally verified v2 model archive." >&2
  exit 2
fi
mkdir -p "$OUTPUT_DIR"
status=0
IFS=',' read -ra configurations <<< "$RUNTIME_CONFIGS"
for configuration in "${configurations[@]}"; do
  provider=${configuration%%:*}
  threads=${configuration#*:}
  if [[ "$provider" != "cpu" && "$provider" != "xnnpack" ]] ||
     ! [[ "$threads" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid runtime configuration: %s\n' "$configuration" >&2
    status=2
    continue
  fi
  if [[ "$provider" == "cpu" ]]; then
    id="cpu-intra-${threads}-inter-1"
  else
    id="xnnpack-threads-${threads}"
  fi
  printf 'configuration=%s\n' "$id"
  set +e
  MODEL_PACKAGE="$MODEL_PACKAGE" \
  WORKLOAD_SECONDS="$WORKLOAD_SECONDS" \
  RUNTIME_PROVIDER="$provider" \
  RUNTIME_THREADS="$threads" \
  BENCHMARK_TASK="build-serbian-audiobook-mvp-5.2" \
  OUTPUT="$OUTPUT_DIR/$id-benchmark.json" \
  "$ROOT/scripts/run_android_benchmark.sh"
  entry_status=$?
  set -e
  if (( entry_status != 0 )); then
    status=1
  else
    jq -r '"real_time_factor=\(.measurements.real_time_factor) peak_process_memory_bytes=\(.measurements.peak_process_memory_bytes)"' \
      "$OUTPUT_DIR/$id-benchmark.json"
  fi
done
exit "$status"
