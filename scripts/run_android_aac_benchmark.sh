#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ADB=${ADB:-"$SDK/platform-tools/adb"}
DEVICE=${DEVICE:-emulator-5554}
OUTPUT=${OUTPUT:-/tmp/citac-knjiga-android-aac-benchmark-report.json}
AAC_BITRATES_BPS=${AAC_BITRATES_BPS:-64000,80000,96000}
BENCHMARK_TASK=${BENCHMARK_TASK:-build-serbian-audiobook-mvp-10.1}
APP_ID=com.homoludens.citacknjiga.debug
TEST_ID=com.homoludens.citacknjiga.debug.test

if [[ -z "$SDK" || ! -x "$ADB" ]]; then
  printf '%s\n' "Set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK containing platform-tools/adb." >&2
  exit 2
fi
if [[ "$AAC_BITRATES_BPS" =~ [^0-9,] || "$AAC_BITRATES_BPS" == ,* || "$AAC_BITRATES_BPS" == *, || "$AAC_BITRATES_BPS" == *,,* ]]; then
  printf '%s\n' "AAC_BITRATES_BPS must be a comma-separated list of positive bitrates." >&2
  exit 2
fi
if [[ "$BENCHMARK_TASK" =~ [[:space:]] ]]; then
  printf '%s\n' "BENCHMARK_TASK must not contain whitespace." >&2
  exit 2
fi

WIFI_ON=$([ "$($ADB -s "$DEVICE" shell settings get global wifi_on | tr -d '\r')" = "1" ] && printf 1 || printf 0)
DATA_ON=$([ "$($ADB -s "$DEVICE" shell settings get global mobile_data | tr -d '\r')" = "1" ] && printf 1 || printf 0)
cleanup() {
  "$ADB" -s "$DEVICE" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell run-as "$APP_ID" rm -rf files/benchmark-reports >/dev/null 2>&1 || true
  if [[ "$WIFI_ON" == "1" ]]; then "$ADB" -s "$DEVICE" shell svc wifi enable >/dev/null 2>&1 || true; else "$ADB" -s "$DEVICE" shell svc wifi disable >/dev/null 2>&1 || true; fi
  if [[ "$DATA_ON" == "1" ]]; then "$ADB" -s "$DEVICE" shell svc data enable >/dev/null 2>&1 || true; else "$ADB" -s "$DEVICE" shell svc data disable >/dev/null 2>&1 || true; fi
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
"$ADB" -s "$DEVICE" shell svc wifi disable >/dev/null 2>&1 || true
"$ADB" -s "$DEVICE" shell svc data disable >/dev/null 2>&1 || true

set +e
"$ADB" -s "$DEVICE" shell am instrument -w -r \
  -e aac_benchmark true \
  -e aac_bitrates_bps "$AAC_BITRATES_BPS" \
  -e benchmark_task "$BENCHMARK_TASK" \
  -e class com.homoludens.citacknjiga.benchmark.AacBenchmarkAndroidTest#benchmarksPlatformAacWhenExplicitlyRequested \
  "$TEST_ID/androidx.test.runner.AndroidJUnitRunner" \
  2>&1 | tee "$OUTPUT.instrumentation.log"
TEST_STATUS=${PIPESTATUS[0]}
set -e

if ! "$ADB" -s "$DEVICE" exec-out run-as "$APP_ID" cat files/benchmark-reports/android-aac-benchmark-report.json > "$OUTPUT"; then
  printf '%s\n' "AAC benchmark did not produce a report." >&2
  exit 1
fi
printf 'report=%s\n' "$OUTPUT"
if ! jq -e '
  .kind == "android-aac-m4a-benchmark" and
  .report_version == 1 and
  (.input.sample_rate_hz == 24000) and
  (.input.channels == 1) and
  (.bitrates | length > 0) and
  (.status == "completed" or .status == "blocked" or .status == "failed")
' "$OUTPUT" >/dev/null; then
  printf 'Invalid AAC benchmark report: %s\n' "$OUTPUT" >&2
  exit 1
fi
if [[ "$TEST_STATUS" -ne 0 ]]; then
  exit "$TEST_STATUS"
fi
STATUS=$(jq -r '.status' "$OUTPUT")
if [[ "$STATUS" == "blocked" ]]; then
  printf '%s\n' "AAC benchmark blocked: no requested platform bitrate was available." >&2
  exit 3
fi
if [[ "$STATUS" == "failed" ]]; then
  printf '%s\n' "AAC benchmark failed; inspect the report and instrumentation log." >&2
  exit 1
fi
