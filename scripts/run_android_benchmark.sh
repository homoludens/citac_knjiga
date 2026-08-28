#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ADB=${ADB:-"$SDK/platform-tools/adb"}
DEVICE=${DEVICE:-2555a240}
MODEL_PACKAGE=${MODEL_PACKAGE:-}
OUTPUT=${OUTPUT:-/tmp/citac-knjiga-android-benchmark-report.json}
WORKLOAD_SECONDS=${WORKLOAD_SECONDS:-900}
APP_ID=com.homoludens.citacknjiga.debug
TEST_ID=com.homoludens.citacknjiga.debug.test
REMOTE=/data/local/tmp/citac-knjiga-task-5-1
EXPECTED_PACKAGE_SHA256=58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b

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
  -e class com.homoludens.citacknjiga.benchmark.AndroidBenchmarkTest#runsFifteenMinuteTypedInputBenchmark \
  "$TEST_ID/androidx.test.runner.AndroidJUnitRunner"
TEST_STATUS=$?
set -e
"$ADB" -s "$DEVICE" exec-out run-as "$APP_ID" cat files/benchmark-reports/android-benchmark-report.json > "$OUTPUT"
printf 'report=%s\n' "$OUTPUT"
exit "$TEST_STATUS"
