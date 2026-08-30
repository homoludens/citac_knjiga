# Task 11.4 Android Qualification Evidence

Status: **blocked, task remains unchecked**.

Run date: 2026-08-30. Host repository before this evidence commit: `9f3a37d`.

## Matrix

| Target | Availability | Result | Limitation |
|---|---|---|---|
| Android 11 (API 30) | Blocked | Not run | No API 30 system image or connected API 30 device. API 30 Google API packages are downloadable, but installed API 29 is Android 10 and is not substituted. |
| Current Android release | Available as API 35 emulator | Playback control only | `emulator-5554`, Google `sdk_gphone64_x86_64` / `emu64xa`, x86_64, fingerprint `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`. Production benchmark rejects this target because it requires Poco F3 native ARM64. |
| Android 16 (API 36) | Blocked | Not run | API 36 platform is installed, but no API 36 system image/AVD or connected API 36 device exists. API 36 Google API x86_64 and ARM64 images are only listed as downloadable packages. |
| Poco F3 vendor battery-management configuration | Blocked | Not run | No physical device connected. The known device is Xiaomi `M2012K11AG` / `alioth`, Android 13/API 33, native `arm64-v8a`, not Android 16; no current vendor battery-management observation is available. |

## Exact Runs

1. `ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk ./gradlew :playback-export:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.playback.export.ProgressivePlaybackAndroidTest --no-daemon --max-workers=1`
   - Result: **pass**, `BUILD SUCCESSFUL`, one test, zero failures, 48 seconds.
   - Evidence: Room/atomic publication plus Media3 queue continuity; chapter 1 continued playing while deterministic synthetic chapter 2 generation was released.
   - This is a playback continuity control, not production Serbian model generation or sustained qualification.
2. `MODEL_PACKAGE=/tmp/citac-knjiga-public-package-20260828/kokoro-serbian-dragana-v2.zip DEVICE=emulator-5554 ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk WORKLOAD_SECONDS=15 OUTPUT=/tmp/citac-knjiga-task-11-4-benchmark-precondition.json scripts/run_android_benchmark.sh`
   - Result: **blocked before execution**, exit status 2: `The benchmark is restricted to the Poco F3 (M2012K11AG/alioth).`
   - The supplied external package was present and SHA-256 verified as `58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`.

## Observations and Gaps

- The API 35 emulator inventory snapshot reported battery 100%, battery sensor 25.0 C, Android thermal status 0, low-power mode 0, and no app-specific background exemption. These are pre-run emulator observations, not sustained production measurements.
- No production generation progress, generated-audio continuity, process death, force-stop, reboot, or app-update behavior was obtained in this task. The connected control uses synthetic audio and does not exercise the production model.
- No Poco vendor battery restrictions, vendor thermal zones, or MIUI background policy could be observed without the physical device. Existing historical Poco API 33 benchmark evidence is not a substitute for this task's combined sustained qualification.
- No Android 11 or Android 16 target was fabricated from API 29, API 35, or the installed API 36 platform SDK.

## Reproduction

Run `scripts/run_android_qualification_matrix.sh` with `ANDROID_HOME`/`ANDROID_SDK_ROOT` and optionally `MODEL_PACKAGE` to regenerate the environment inventory. Keep reports, model packages, and audio artifacts outside the repository. Re-run the two commands above only when compatible targets are attached; do not remove the benchmark's Poco/ARM64 guards.
