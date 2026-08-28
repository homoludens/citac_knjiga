#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ADB=${ADB:-"$SDK/platform-tools/adb"}
DEVICE=${DEVICE:-2555a240}
MODEL_PACKAGE=${MODEL_PACKAGE:-}
OUTPUT=${OUTPUT:-/tmp/citac-knjiga-android-benchmark-report.json}
WORKLOAD_SECONDS=${WORKLOAD_SECONDS:-900}
RUNTIME_PROVIDER=${RUNTIME_PROVIDER:-cpu}
RUNTIME_THREADS=${RUNTIME_THREADS:-1}
BENCHMARK_TASK=${BENCHMARK_TASK:-build-serbian-audiobook-mvp-5.1}
APP_ID=com.homoludens.citacknjiga.debug
TEST_ID=com.homoludens.citacknjiga.debug.test
REMOTE=/data/local/tmp/citac-knjiga-runtime-measurement
EXPECTED_PACKAGE_SHA256=58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b

case "$RUNTIME_PROVIDER" in
  cpu|xnnpack) ;;
  *) printf '%s\n' "RUNTIME_PROVIDER must be cpu or xnnpack." >&2; exit 2 ;;
esac
if ! [[ "$RUNTIME_THREADS" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' "RUNTIME_THREADS must be a positive integer." >&2
  exit 2
fi
if [[ "$BENCHMARK_TASK" =~ [[:space:]] ]]; then
  printf '%s\n' "BENCHMARK_TASK must not contain whitespace." >&2
  exit 2
fi

if [[ -z "$SDK" || ! -x "$ADB" ]]; then
  printf '%s\n' "Set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK containing platform-tools/adb." >&2
  exit 2
fi
if [[ -z "$MODEL_PACKAGE" || ! -f "$MODEL_PACKAGE" ]]; then
  printf '%s\n' "Set MODEL_PACKAGE to the locally verified v2 model archive." >&2
  exit 2
fi
if [[ "$(sha256sum "$MODEL_PACKAGE" | cut -d' ' -f1)" != "$EXPECTED_PACKAGE_SHA256" ]]; then
  printf '%s\n' "MODEL_PACKAGE is not the verified local v2 archive." >&2
  exit 2
fi

device_prop() { "$ADB" -s "$DEVICE" shell getprop "$1" | tr -d '\r'; }
if [[ "$(device_prop ro.product.model)" != "M2012K11AG" || "$(device_prop ro.product.device)" != "alioth" ]]; then
  printf '%s\n' "The benchmark is restricted to the Poco F3 (M2012K11AG/alioth)." >&2
  exit 2
fi
if [[ "$(device_prop ro.product.cpu.abi)" != "arm64-v8a" ]]; then
  printf '%s\n' "The benchmark requires the native arm64-v8a process." >&2
  exit 2
fi

WIFI_ON=$("$ADB" -s "$DEVICE" shell settings get global wifi_on | tr -d '\r')
DATA_ON=$("$ADB" -s "$DEVICE" shell settings get global mobile_data | tr -d '\r')
cleanup() {
  "$ADB" -s "$DEVICE" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell run-as "$APP_ID" rm -rf files/benchmark-reports files/model-packages >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell rm -rf "$REMOTE" >/dev/null 2>&1 || true
  if [[ "$WIFI_ON" == "1" ]]; then "$ADB" -s "$DEVICE" shell svc wifi enable >/dev/null; else "$ADB" -s "$DEVICE" shell svc wifi disable >/dev/null; fi
  if [[ "$DATA_ON" == "1" ]]; then "$ADB" -s "$DEVICE" shell svc data enable >/dev/null; else "$ADB" -s "$DEVICE" shell svc data disable >/dev/null; fi
}
trap cleanup EXIT

mkdir -p "$(dirname "$OUTPUT")"
ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" "$ROOT/gradlew" \
  :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest
"$ADB" -s "$DEVICE" install --no-streaming -r \
  "$ROOT/app/build/outputs/apk/standard/debug/app-standard-debug.apk" >/dev/null
"$ADB" -s "$DEVICE" install --no-streaming -r \
  "$ROOT/app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk" >/dev/null
"$ADB" -s "$DEVICE" shell pm clear "$APP_ID" >/dev/null
"$ADB" -s "$DEVICE" shell rm -rf "$REMOTE"
"$ADB" -s "$DEVICE" shell mkdir -p "$REMOTE"
"$ADB" -s "$DEVICE" push "$MODEL_PACKAGE" "$REMOTE/model-package.zip" >/dev/null
"$ADB" -s "$DEVICE" shell run-as "$APP_ID" mkdir -p files/model-packages
"$ADB" -s "$DEVICE" shell run-as "$APP_ID" cp "$REMOTE/model-package.zip" files/model-packages/active.zip
"$ADB" -s "$DEVICE" shell svc wifi disable
"$ADB" -s "$DEVICE" shell svc data disable
set +e
"$ADB" -s "$DEVICE" shell am instrument -w -r \
  -e benchmark true \
  -e workload_seconds "$WORKLOAD_SECONDS" \
  -e runtime_provider "$RUNTIME_PROVIDER" \
  -e runtime_threads "$RUNTIME_THREADS" \
  -e benchmark_task "$BENCHMARK_TASK" \
  -e class com.homoludens.citacknjiga.benchmark.AndroidBenchmarkTest#runsFifteenMinuteTypedInputBenchmark \
  "$TEST_ID/androidx.test.runner.AndroidJUnitRunner" \
  2>&1 | tee "$OUTPUT.instrumentation.log"
TEST_STATUS=$?
set -e
"$ADB" -s "$DEVICE" exec-out run-as "$APP_ID" cat files/benchmark-reports/android-benchmark-report.json > "$OUTPUT"
printf 'report=%s\n' "$OUTPUT"
if ! jq -e \
  --arg provider "$RUNTIME_PROVIDER" \
  --argjson threads "$RUNTIME_THREADS" \
  --arg task "$BENCHMARK_TASK" \
  '(
    .status == "completed" and
    .completed == true and
    .task == $task and
    .runtime.executionProvider == $provider and
    (if $provider == "cpu"
     then .runtime.intraOpThreads == $threads and .runtime.interOpThreads == 1
     else .runtime.providerThreads == $threads
     end) and
    (.measurements.real_time_factor | type == "number" and . >= 0) and
    (.measurements.peak_process_memory_bytes | type == "number" and . > 0) and
    (.workload.inference_calls | type == "number" and . > 0) and
    (.workload.audio_seconds_generated >= .workload.target_audio_seconds)
  )' "$OUTPUT" >/dev/null 2>&1; then
  printf 'Invalid or mismatched benchmark report: %s\n' "$OUTPUT" >&2
  TEST_STATUS=1
fi
exit "$TEST_STATUS"
