# Task 12.8 Release Gate

Status: **publication refused; task remains unchecked**.

Run date: 2026-08-30. The checker is `scripts/check_release_gate.py`. It is
read-only and does not build, sign, upload, publish, create a GitHub release,
or use signing credentials. No model package, audio, APK, or gate JSON is stored
in this repository.

## Current Gate Result

| Gate | Class | Status | Evidence / reason |
|---|---|---|---|
| Signed app artifacts | Hard | BLOCKED | Task 12.7 is unchecked; no signed artifact directory or signing credentials are available. |
| Model legal status | Hard | BLOCKED | Model manifest is blocked for public distribution; Juzne vesti DUA, ShareAlike, and underlying broadcast-rights review remain open. |
| Desktop parity | Hard | PASS | Committed `fp32-parity-v2` report evaluates 26/26 vectors. |
| Android parity | Hard | PASS | Historical Poco F3 ARM64 report passed 26/26; the report is device-private and model artifacts remain external. |
| Real production model proof | Hard | PASS | Task 4.10 records the complete production graph on Poco F3 with networking disabled. |
| External-player portability | Hard | BLOCKED | Task 10.8 is unchecked; two external Android audio players were unavailable. |
| Android/device qualification | Hard | BLOCKED | Task 11.4 is unchecked; Android 11, Android 16, and Poco vendor battery-management evidence are missing. |
| Capability release audit | Hard | BLOCKED | Task 11.8 is unchecked and its audit says not to declare an MVP release candidate. |
| Dependency/privacy/F-Droid | Hard | PASS | Recorded dependency, privacy, notices, and F-Droid substitute checks pass; no real external scanner result is claimed. |
| Recovery/export/instrumentation | Hard | PASS | Tasks 8.9, 10.5-10.7, and 12.2 evidence is recorded. |
| OpenSpec strict validation | Hard | PASS | `openspec validate build-serbian-audiobook-mvp --strict` passes. |
| App/model/audio separation | Hard | PASS | Release contract excludes optional model packages, generated audio, and secrets from app artifacts. |
| Benchmark measurements | Informational | INFO | RTF, memory, thermal, and battery observations have no acceptance threshold and do not satisfy task 11.4. |

Publication is refused because at least one hard gate is not `PASS`. The
historical parity and production-model proofs are not substituted for the
missing release artifacts, legal clearance, portability, device matrix, or
capability audit.

## Reproduction

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  python3 scripts/check_release_gate.py
```

The command returns exit status 1 for this repository. It runs strict OpenSpec,
release-document, and F-Droid checks without generating release or model/audio
artifacts. Future signed app artifacts and cleared model/parity evidence must
be supplied from outside the repository; an unsigned build or declaration-only
model fixture cannot satisfy the gate.

## Verification Runs

- `openspec validate build-serbian-audiobook-mvp --strict`: pass.
- `model-tools/.venv/bin/python -m pytest model-tools/tests`: 39 passed.
- `python3 -m unittest scripts/test_release_gate.py scripts/test_release_artifacts.py`: 9 passed.
- `scripts/check_fdroid.py --require-build` and `scripts/validate_release_docs.py`: pass with the locked Android SDK.
- Gradle `test check`, release lint, offline manifest verification, and standard/F-Droid release assemblies: pass.
- `scripts/run_android_instrumentation.sh`: blocked before tests because `emulator-5554` was not connected and ready.
- `scripts/check_external_audio_players.sh`: blocked; connected API 35 target exposed zero external local-audio handlers.
- `scripts/run_android_qualification_matrix.sh`: inventory written outside the repository; required Android 11, Android 16, and Poco evidence remains unavailable.
