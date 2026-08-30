#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ADB=${ADB:-"$SDK/platform-tools/adb"}
DEVICE=${DEVICE:-emulator-5554}
REQUIRED_API=${REQUIRED_API:-35}

if [[ -z "$SDK" || ! -x "$ADB" ]]; then
  printf '%s\n' "Set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK containing platform-tools/adb." >&2
  exit 2
fi
if [[ "$($ADB -s "$DEVICE" get-state 2>/dev/null || true)" != "device" ]]; then
  printf 'Device %s is not connected and ready.\n' "$DEVICE" >&2
  exit 2
fi
API=$($ADB -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$API" != "$REQUIRED_API" ]]; then
  printf 'Device %s is API %s; this task runner requires API %s.\n' "$DEVICE" "$API" "$REQUIRED_API" >&2
  exit 2
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_SERIAL="$DEVICE"
GRADLE=("$ROOT/gradlew" --no-daemon --max-workers=1)

"${GRADLE[@]}" \
  :core:compileDebugAndroidTestKotlin \
  :document-epub:compileDebugAndroidTestKotlin \
  :playback-export:compileDebugAndroidTestKotlin \
  :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest

"${GRADLE[@]}" :core:connectedDebugAndroidTest
"${GRADLE[@]}" :document-epub:connectedDebugAndroidTest
"${GRADLE[@]}" :playback-export:connectedDebugAndroidTest
"${GRADLE[@]}" :app:connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.generation.MultiChapterResumeAndroidTest
