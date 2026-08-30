#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ADB=${ADB:-"$SDK/platform-tools/adb"}
SDKMANAGER=${SDKMANAGER:-"$SDK/cmdline-tools/latest/bin/sdkmanager"}
AVDMANAGER=${AVDMANAGER:-"$SDK/cmdline-tools/latest/bin/avdmanager"}
REPORT=${REPORT:-/tmp/citac-knjiga-task-11-4-matrix.md}
MODEL_PACKAGE=${MODEL_PACKAGE:-}

if [[ -z "$SDK" || ! -x "$ADB" ]]; then
  printf '%s\n' "Set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK containing platform-tools/adb." >&2
  exit 2
fi

mkdir -p "$(dirname "$REPORT")"

image_rows() {
  local properties image relative
  shopt -s nullglob
  for properties in "$SDK"/system-images/*/*/*/source.properties; do
    image=${properties%/source.properties}
    relative=${image#"$SDK"/}
    printf '%s\n' "- \`$relative\`"
  done
  shopt -u nullglob
}

available_image_rows() {
  local line
  if [[ ! -x "$SDKMANAGER" ]]; then
    printf '%s\n' "- sdkmanager unavailable at \`$SDKMANAGER\`"
    return
  fi
  "$SDKMANAGER" --list 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *"system-images;android-30;"*|*"system-images;android-36;"*)
        printf '%s\n' "- $line"
        ;;
    esac
  done
}

device_rows() {
  local serial state rest sdk release manufacturer model device abi fingerprint
  while read -r serial state rest; do
    [[ "$serial" == "List" || -z "$serial" ]] && continue
    if [[ "$state" != "device" ]]; then
      printf '%s\n' "- \`$serial\`: state=$state"
      continue
    fi
    sdk=$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
    release=$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')
    manufacturer=$("$ADB" -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')
    model=$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r')
    device=$("$ADB" -s "$serial" shell getprop ro.product.device | tr -d '\r')
    abi=$("$ADB" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
    fingerprint=$("$ADB" -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')
    printf '%s\n' "- \`$serial\`: $manufacturer $model / $device, Android $release (API $sdk), ABI \`$abi\`, fingerprint \`$fingerprint\`"
  done < <("$ADB" devices)
}

{
  printf '%s\n' '# Android qualification matrix inventory (task 11.4)'
  printf '%s\n' "Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf '%s\n' "Repository: \`$ROOT\`"
  printf '%s\n' "HEAD: \`$(git -C "$ROOT" rev-parse HEAD)\`"
  printf '%s\n' "SDK: \`$SDK\`"
  printf '%s\n' '' '## Installed system images'
  image_rows
  printf '%s\n' '' '## Relevant downloadable system-image packages (not evidence of installation)'
  available_image_rows
  printf '%s\n' '' '## AVD inventory'
  if [[ -x "$AVDMANAGER" ]]; then
    "$AVDMANAGER" list avd 2>&1 | while IFS= read -r line; do printf '%s\n' "- $line"; done
  else
    printf '%s\n' "- avdmanager unavailable at \`$AVDMANAGER\`"
  fi
  printf '%s\n' '' '## Connected devices'
  device_rows
  printf '%s\n' '' '## Model package'
  if [[ -n "$MODEL_PACKAGE" && -f "$MODEL_PACKAGE" ]]; then
    printf '%s\n' "- present: \`$MODEL_PACKAGE\`"
    printf '%s\n' "- SHA-256: \`$(openssl dgst -sha256 -r "$MODEL_PACKAGE" | cut -d' ' -f1)\`"
  else
    printf '%s\n' "- unavailable (set \`MODEL_PACKAGE\` to inspect the external verified archive)"
  fi
  printf '%s\n' '' '## Target interpretation'
  printf '%s\n' '- Android 11 qualification requires a connected API 30 device or an installed API 30 system image plus a booted AVD.'
  printf '%s\n' '- Current-release qualification requires a connected current-release device; an emulator is reported with its exact API/model/ABI.'
  printf '%s\n' '- Android 16 qualification requires a connected API 36 device or booted API 36 system-image AVD.'
  printf '%s\n' '- Poco F3 vendor qualification requires native ARM64 device properties M2012K11AG/alioth.'
  printf '%s\n' '' '## Existing run commands'
  printf '%s\n' '- Playback continuity control: `ANDROID_HOME=/path/to/sdk ANDROID_SDK_ROOT=/path/to/sdk ./gradlew :playback-export:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.playback.export.ProgressivePlaybackAndroidTest`'
  printf '%s\n' '- Production sustained generation benchmark: `MODEL_PACKAGE=/path/to/verified.zip DEVICE=<poco-serial> WORKLOAD_SECONDS=900 scripts/run_android_benchmark.sh`'
  printf '%s\n' '- This inventory does not change target guards and does not claim a qualification pass.'
} > "$REPORT"

printf 'report=%s\n' "$REPORT"
