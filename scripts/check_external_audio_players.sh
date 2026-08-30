#!/usr/bin/env bash
set -euo pipefail

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ADB=${ADB:-"$SDK/platform-tools/adb"}

if [[ -z "$SDK" || ! -x "$ADB" ]]; then
  printf '%s\n' "Set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK containing platform-tools/adb." >&2
  exit 2
fi

mapfile -t devices < <(
  "$ADB" devices | while read -r serial state _; do
    [[ "$state" == "device" ]] && printf '%s\n' "$serial"
  done
)
if ((${#devices[@]} == 0)); then
  printf '%s\n' "No connected Android devices or emulators." >&2
  exit 3
fi

qualifying_device=0
for device in "${devices[@]}"; do
  printf 'device=%s model=%s sdk=%s\n' \
    "$device" \
    "$("$ADB" -s "$device" shell getprop ro.product.model | tr -d '\r')" \
    "$("$ADB" -s "$device" shell getprop ro.build.version.sdk | tr -d '\r')"
  printf '%s\n' 'third_party_packages='
  "$ADB" -s "$device" shell pm list packages -3 | tr -d '\r' | sort

  declare -A handlers=()
  for mime in audio/mp4 audio/aac audio/m4a audio/x-m4a; do
    while IFS= read -r component; do
      component=${component//$'\r'/}
      [[ "$component" == */* ]] || continue
      package=${component%%/*}
      [[ "$package" == com.homoludens.citacknjiga.* ]] && continue
      handlers["$package"]="$component"
    done < <(
      "$ADB" -s "$device" shell cmd package query-activities --brief --components \
        -a android.intent.action.VIEW -t "$mime" 2>/dev/null || true
    )
  done

  if ((${#handlers[@]} == 0)); then
    printf '%s\n' 'external_audio_player_packages=none'
  else
    ((${#handlers[@]} >= 2)) && qualifying_device=1
    printf '%s\n' 'external_audio_player_packages='
    for package in "${!handlers[@]}"; do
      printf '%s\n' "${handlers[$package]}"
    done | sort
  fi
done

if ((qualifying_device == 0)); then
  printf '%s\n' "BLOCKED: fewer than two external local-audio handlers are available; task 10.8 remains unchecked." >&2
  exit 3
fi
