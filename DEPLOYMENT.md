# Deployment & Environment — citac_knjiga

Known-good steps to reproduce the desktop CPU inference path. Keep this file
current as the environment changes.

## Desktop model tooling (`model-tools/`)

The reference CPU inference path uses the **pinned Kokoro runtime** (see
`model-tools/runtime-pins.md`) with Python 3.11 and espeak-ng.

### One-time setup

1. **System prerequisites**
   - `git-lfs` (weights are LFS-tracked): `git lfs install`
   - `espeak-ng` (Serbian phonemizer, `-v sr`): present at `/usr/bin/espeak-ng`
     v1.52.0, data at `/usr/share/espeak-ng-data`.
   - `uv` (Python package/env manager).

2. **Environment (locked)**
   ```bash
   cd model-tools
   uv sync --python 3.11        # creates .venv, resolves + locks (uv.lock)
   ```
   - `uv.lock` (310 packages) pins the exact versions, including
     `kokoro @ git+https://github.com/semidark/kokoro.git@b96fef95...`,
     `torch==2.13.0` (the CUDA build is fine for CPU inference; the reference
     samples are CPU-generated), and the ONNX tooling resolved for task 2.2:
     `onnx==1.22.0`, `onnxruntime==1.29.0` (CPU), `onnxscript==0.7.1`.
     The FP32 export uses the **legacy TorchScript exporter** (`dynamo=False`,
     `dynamic_axes`), CustomSTFT (`disable_complex=True`), pinned `ai.onnx`
     opset **18**. See `model-tools/export/README.md`.
   - **Do not replace the pinned kokoro with PyPI `kokoro==0.9.4`** — the
     weight-norm implementation differs and produces noise. The smoke test
     asserts the weight-norm guard.

3. **The pinned `kokoro` package and `kokoro_sr` phonemizer live in the
   training repo**, not in this venv. `scripts/smoke_inference.py` inserts
   them on `sys.path` at the exact pinned paths:
   - `kokoro` package:
     `/home/homoludens/projekti/kokoro_tts_srpski_2/workspace/kokoro-serbian/runtime/upstream/kokoro-training/`
   - `kokoro_sr` source: `/home/homoludens/projekti/kokoro_tts_srpski_2/src/`
   - If those paths move, update the two `Path(...)` constants in
     `scripts/smoke_inference.py` (and `speak_2.py`).

### Verify (smoke test)

```bash
cd model-tools
.venv/bin/python scripts/smoke_inference.py
```

Expected (2026-08-26, this desktop): `ok: true`, model/voice SHA-256 match the
bundle, voice shape `[510,1,256]`, finite float32 audio, peak < 1.0, 24 kHz.
Writes `model-tools/reference/smoke-test.wav` (LFS-tracked).

### Reference artifacts

- `model-tools/reference/` — LFS-tracked reference captures and the expanded
  task-3.3 golden corpus. Corpus regeneration and validation are documented in
  `model-tools/reference/README.md`.
- `model-tools/runtime-pins.md` — immutable runtime/source/bundle identity.
- `model-tools/legal-inventory.md` — data/weight licensing + release gate.
- `model-tools/dependency-inventory.md` — dependency/license inventory template.

## Android inference runtime target (task 2.8)

The selected implementation target is the exact Maven Central release:

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
```

Do not use `latest.release`, a dynamic version, nightly build, or Sherpa-ONNX
for the MVP runtime. The inspected AAR SHA-256 is
`e97540ca78fe36f6fe2013f82843414fb843b6c7681fb04644cba5e1406662dd`.
Record that checksum in Gradle dependency verification when the Android
project is created; dependency locking and checksum verification are release
requirements for task 12.3.

The app target is Android 11+ `arm64-v8a`, so configure an explicit ABI filter.
The initial session baseline is CPU execution with sequential ORT execution and
one intra-op plus one inter-op thread. XNNPACK is a separately measured,
explicitly configured variant with CPU fallback. NNAPI remains an optional
future optimization.

This is a selected dependency target, not Android graph parity, ABI loading,
performance, thermal, or Poco F3 qualification. See
`model-tools/android-runtime-decision.md` before implementing the Android
module.

## Android Serbian phonemizer (task 4.5)

The `tts-onnx` module builds eSpeak-NG `1.52.0` from source commit
`4870adfa25b1a32b4361592f1be8a40337c58d6c` for `arm64-v8a`. NDK
`26.1.10909125`, Android API 30, and the native data closure are pinned in
`model-tools/native/espeak-data-manifest-v1.json`. The source-build and GPL
notice are in `model-tools/native/NOTICE.md`.

The data closure is installed into app-private storage and verified by size and
SHA-256 before JNI use. The native bridge intentionally reproduces the pinned
`espeak-ng -q --ipa=3 -v sr --stdin` bulk-input behavior, including its final
byte removal, then Kotlin applies the checked-in IPA normalization and
vocabulary contract.

Build the native target directly when validating the toolchain:

```sh
cmake -S tts-onnx/src/main/cpp -B /tmp/cita-espeak-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release \
  -DESPEAK_NG_SOURCE_DIR=/path/to/espeak-ng
cmake --build /tmp/cita-espeak-android --parallel
```

The production ARM64 path remains unqualified until the Android smoke and full
26-vector tests run on an ARM64 device or emulator. Do not treat a desktop or
manual native build alone as ARM64 evidence.

The first connected test attempt on the available API 35 emulator used the
ARM64-only debug package and failed before JUnit discovery. The crash log shows
the x86_64 process using the ARM64 guest/native-bridge path, with a SIGSEGV in
the `libonnxruntime.so` constructor (`fault addr 0x8`, `0 tests`). This is an
emulator/ORT translation failure, not an eSpeak-NG load or data failure.

Debug is explicitly built for native `x86_64` and `arm64-v8a` and carries the
pinned ORT dependency for the development APK; release remains explicitly
native `arm64-v8a` and carries ORT as well. The x86_64 debug package therefore
exercises the eSpeak-NG JNI bridge and ORT natively without changing the
production ABI target, while the same debug APK can run on the target ARM64
device. The runner is explicitly
`androidx.test.runner.AndroidJUnitRunner`; without that setting the platform
runner reports `OK (0 tests)`.

The connected test command is:

```sh
ANDROID_HOME=/path/to/Android/Sdk \
ANDROID_SDK_ROOT=/path/to/Android/Sdk \
PATH=/path/to/Android/Sdk/platform-tools:$PATH \
./gradlew :tts-onnx:connectedDebugAndroidTest
```

On 2026-08-27 this command ran 4 tests successfully on `emulator-5554`; the
smoke test covers five representative vectors and the full test iterates all
26 vectors. This completes task 4.5 for the currently available x86_64 Android
test execution. It does not qualify the ARM64 eSpeak-NG path used by debug or
release builds; rerun the same tests on an ARM64 device or native ARM64
emulator before treating the production path as qualified.

## Android tensor boundary (task 4.6)

The direct runtime implementation is `tts-onnx/src/main/java/com/homoludens/
citacknjiga/tts/onnx/OnnxTtsSession.kt`. It consumes the imported package's
verified `model` and `voice_style` artifacts, uses the manifest boundary
`input_ids` int64 `[1, seq_len]`, `ref_s` float32 `[1, 256]`, and scalar
`speed` float32, and returns named `waveform` float32 PCM plus `pred_dur`
int64. The baseline is sequential CPU execution with ORT intra-op/inter-op
threads `1/1`; all input tensors, results, options, sessions, and environments
are closed on success and failure.

Focused checks:

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SDK_ROOT=/path/to/Android/Sdk \
  ./gradlew :tts-onnx:testDebugUnitTest :tts-onnx:lintDebug \
  :tts-onnx:assembleDebug :tts-onnx:assembleRelease \
  :tts-onnx:connectedDebugAndroidTest
```

The connected boundary test uses a small deterministic fixture and does not
add the 326 MB local production graph to Android test assets. The production
graph is present only as an ignored local export, while the legal-blocked model
package is not checked in; production-graph Android parity and device
qualification therefore remain later gates.

The boundary validator rejects non-finite or silent PCM, samples at or beyond
full scale, non-24 kHz/non-mono metadata, inconsistent sample counts, and
`pred_dur` values outside the model's declared minimum and speed-scaled
`max_dur=50` range. Its JVM and Android tests use synthetic PCM and do not load
the local production graph.

The production model entry is streamed to an app-private temporary file and
opened with ONNX Runtime's path-based session API. It is deleted after session
creation and on all session-open failure paths. The PyTorch voice archive is
read with `ZipFile`: Android's streaming ZIP reader does not reliably enumerate
its stored entries with data descriptors.

## Voice bundle

`kokoro_sr_dragana_voice/` is the current known-good epoch-005 Dragana bundle
(LFS-tracked `.pth`/`.pt`). `python_voice_test/` is an older epoch_2nd_00002
export retained for provenance. See `runtime-pins.md` §4.

## Typed Serbian proof screen (task 4.8)

The single Compose route accepts typed Latin or Cyrillic Serbian text and uses
the native eSpeak-NG preprocessing bridge followed by `OnnxTtsSession`. A
verified model package must be imported into app-private storage first; the
application does not bundle the production model and never fabricates audio.
Successful output is atomically written as validated PCM16, 24 kHz mono WAV
under `filesDir/typed-proof/` and streamed locally with `AudioTrack`. This is a
proof artifact, not durable generation or Media3 playback.

## Android parity report (task 4.9)

`tts-onnx` provides `AndroidDeviceParityRunner`, `DeviceParityReportStore`,
and `DesktopOnnxParityVectorLoader`. The runner compares caller-supplied desktop ONNX
waveforms with Android `OnnxTtsOutput` using every measurement in the active
`fp32-parity-v2` declaration: exact sample count, waveform error, STFT cosine,
silence, clipping, and invalid-output checks. `runAndPersist` writes
`device-parity-report.json` through a synced temporary file and atomic rename;
the report contains only identities, vector IDs, numeric metrics, thresholds,
and status fields, never document text.

The installed-package entry point is fail-closed:

```kotlin
AndroidDeviceParityRunner().runInstalledAndPersist(
    store, desktopVectors, context, DeviceParityReportStore(reportDirectory),
)
```

The exported graph produces 600 samples per predicted duration frame. V2 changes
only the maximum absolute error ceiling from `0.1` to `0.13`; all exact-count,
MAE, spectral, silence, clipping, finite-output, fail-closed, and all-vector
requirements are unchanged. See `model-tools/parity/fp32-parity-v2-decision.md`.

With no verified package it persists `status: "blocked"` and `ok: false`. The
current connected test is fixture evidence only and uses the API 35 x86_64
emulator:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :tts-onnx:testDebugUnitTest :tts-onnx:connectedDebugAndroidTest
```

Task 4.9 is qualified on the Poco F3 (`Xiaomi M2012K11AG`, `alioth`, API 33,
native `arm64-v8a`) against all 26 freshly generated desktop ONNX vectors. The
app-private report has `status=passed`, exact sample counts, worst MAE
`0.0034103132`, worst maximum error `0.0741992146`, and minimum STFT cosine
`0.9993200098`. Its SHA-256 is
`a048addfc24bb04654590b818034ec75849a1686e2865f8592b9f8c9ccbdb51a`.
The local v2 package archive SHA-256 is
`58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`;
generated package and device-report artifacts remain uncommitted.

Prepare the external desktop input bundle with:

```sh
model-tools/.venv/bin/python model-tools/scripts/export_onnx_vectors.py \
  --output-dir /tmp/citac-knjiga-desktop-onnx-vectors-v2
```

The bundle format and Kotlin loader are documented in
`model-tools/parity/ANDROID-INPUT-PROTOCOL.md`. Supply that bundle and a
verified local model package to the Android runner using the exact staging,
instrumentation, report-pull, and cleanup commands in that protocol. The
opt-in production test runs all 26 vectors only in a native `arm64-v8a`
process and stores the report at the test app's private
`files/parity-reports/device-parity-report.json`. Its synthetic fixture test
remains separate and is not production evidence.

The historical v1 report and decision remain committed. The fresh v2 desktop
report passes 26/26 and is committed alongside the versioned declaration.

## Complete offline typed-text proof (task 4.10)

The complete production graph path was verified on the Poco F3
(`2555a240`, `M2012K11AG`, API 33, native `arm64-v8a`) on 2026-08-28. The
qualified local package archive was verified before private staging with
SHA-256 `58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`.
No model package, APK, or WAV is checked in.

MIUI accepted the debug APK and test APK with non-streaming installation. Use
the following shape for future device runs:

```sh
ADB=/path/to/Android/Sdk/platform-tools/adb
DEVICE=2555a240

"$ADB" -s "$DEVICE" install --no-streaming -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
"$ADB" -s "$DEVICE" install --no-streaming -r app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk
"$ADB" -s "$DEVICE" shell run-as com.homoludens.citacknjiga.debug mkdir -p files/model-packages
"$ADB" -s "$DEVICE" push /private/path/kokoro-serbian-dragana-v2.zip /data/local/tmp/model-package.zip
"$ADB" -s "$DEVICE" shell run-as com.homoludens.citacknjiga.debug cp /data/local/tmp/model-package.zip files/model-packages/active.zip
"$ADB" -s "$DEVICE" shell svc wifi disable
"$ADB" -s "$DEVICE" shell svc data disable
"$ADB" -s "$DEVICE" shell am instrument -w -r \
  -e class com.homoludens.citacknjiga.proof.TypedTextProofAndroidTest#verifiedTextPathProducesAndPlaysTwentyFourKilohertzWav \
  com.homoludens.citacknjiga.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumentation gate passed with networking disabled; it had also passed
during an earlier pre-disable run. The manual proof screen showed successful
status, cleaned/normalized text, phonemes,
token IDs, chunk boundaries, Dragana model provenance, and `24 kHz, mono,
PCM16`. Pressing `Пусти` created an active `AudioTrack` for the app process at
`24000` Hz. The captured WAV metadata was PCM signed 16-bit, mono, 24 kHz,
1.275 seconds, 61,244 bytes, SHA-256
`7c07ef70d63d0c7cad414c4a7f5cdd079ed1475c7e9d9574fd9eb9867391ee93`.

Always remove device-private proof artifacts after the run and restore the
connectivity state:

```sh
"$ADB" -s "$DEVICE" shell am force-stop com.homoludens.citacknjiga.debug
"$ADB" -s "$DEVICE" shell run-as com.homoludens.citacknjiga.debug rm -f files/model-packages/active.zip files/typed-proof/typed-proof.wav
"$ADB" -s "$DEVICE" shell rm -rf /data/local/tmp/citac-knjiga-task-4-10 /data/local/tmp/model-package.zip
"$ADB" -s "$DEVICE" shell rm -f /sdcard/Download/citac-knjiga-debug.apk
"$ADB" -s "$DEVICE" shell svc wifi enable
"$ADB" -s "$DEVICE" shell svc data enable
```

## Sustained Poco F3 measurement (task 5.1)

`AndroidBenchmarkRunner` runs the production typed-input path directly: each
fixed Serbian Latin/Cyrillic input is preprocessed with the packaged native
eSpeak-NG resources and passed to one persistent `OnnxTtsSession` opened from
the verified package. The validated PCM is discarded after each call, so this
measurement does not create document text, audio, or a large generated file.
The default workload is at least 900 seconds of generated 24 kHz mono audio.
Model load is timed separately; RTF includes preprocessing and inference wall
time for the workload. Process memory is sampled as total PSS, CPU is process
elapsed CPU time divided by wall time, battery temperature comes from the
`ACTION_BATTERY_CHANGED` battery sensor, and throttling comes from the Android
aggregate `PowerManager` thermal status.

The report is written atomically to the target app's private
`files/benchmark-reports/android-benchmark-report.json` and pulled to the
host. It contains no source-text field or document URI. The report is
informational and does not gate implementation.

The repeatable wrapper requires the exact locally verified v2 archive
(`58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`), checks
that the device is the native ARM64 Poco F3, builds and installs the standard
debug app/test APKs, disables Wi-Fi and mobile data before instrumentation,
pulls the report, removes device-private package/report files, and restores
the prior Wi-Fi/mobile-data settings:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
MODEL_PACKAGE=/tmp/citac-knjiga-public-package-20260828/kokoro-serbian-dragana-v2.zip \
DEVICE=2555a240 \
OUTPUT=/tmp/citac-knjiga-android-benchmark-report.json \
scripts/run_android_benchmark.sh
```

Use `WORKLOAD_SECONDS=900` only when reproducing the historical sustained task
5.1 measurement. Shorter values are appropriate for development comparisons.
Inspect the pulled JSON before accepting results. Its limitations always state
that total PSS is not portable peak RSS, process CPU lacks vendor scheduler
detail, battery temperature is not SoC/skin temperature, Android thermal
status lacks vendor zones, and battery percentage is a rounded boundary sample.

### Captured Poco F3 run

The 2026-08-28 run completed with `status=completed`, 203 inference calls, and
902.45 generated audio seconds. It used package archive SHA-256
`58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`, package
identity `kokoro-serbian-dragana@1.0.0`, model SHA-256
`f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6`, voice
SHA-256 `0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a`,
and ONNX Runtime Android `1.29.0` CPU sequential `1/1` threads.

| Measurement | Result |
|---|---:|
| Model load | 2,964 ms |
| Workload wall time | 1,594.649 s |
| Real-time factor | 1.767 |
| Peak process PSS | 908,320,768 bytes (887,032 KiB) |
| Process CPU utilization | 114.108% average, 206.336% sampled peak |
| Battery level change | 52% to 50% (-2 percentage points) |
| Battery temperature | 35.7 C to 37.0 C; 35.7 C minimum, 37.0 C maximum |
| Thermal status / throttling | Android status 0 throughout; not observed |

RTF and peak memory are recorded observations without acceptance thresholds;
they do not stop downstream implementation. The pulled machine-readable report was
`/tmp/citac-knjiga-task-5-1-full.json` and is intentionally not committed.

## Short runtime comparison (task 5.2)

The matrix wrapper defaults to 60 generated audio seconds for each controlled
CPU and XNNPACK thread configuration. It reports real-time factor and peak
process memory, stores one benchmark JSON per configuration, and does not run
the 26-vector parity suite. Correctness parity remains covered by task 4.9.

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
MODEL_PACKAGE=/tmp/citac-knjiga-public-package-20260828/kokoro-serbian-dragana-v2.zip \
DEVICE=2555a240 \
scripts/run_android_runtime_matrix.sh
```

Override `WORKLOAD_SECONDS` or `RUNTIME_CONFIGS` when a different comparison is
useful. These measurements have no pass/fail limit and do not block application
implementation.

### Captured Poco F3 matrix

On 2026-08-28, each configuration completed with a 15-second target workload
(18.875 generated audio seconds) on `M2012K11AG`/`alioth`, API 33, native
`arm64-v8a`. The archive was verified as
`58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b` before
staging. Peak process memory is the report's sampled total PSS, not portable
peak RSS.

| Configuration | Real-time factor | Peak process PSS |
|---|---:|---:|
| CPU, intra/inter 1/1 | 1.379 | 869,524,480 bytes |
| CPU, intra/inter 2/1 | 0.976 | 910,154,752 bytes |
| CPU, intra/inter 4/1 | 0.603 | 895,176,704 bytes |
| XNNPACK, provider threads 1 | 1.722 | 901,241,856 bytes |
| XNNPACK, provider threads 2 | 1.680 | 909,795,328 bytes |
| XNNPACK, provider threads 4 | 1.698 | 886,729,728 bytes |

All six reports had `status=completed`, matching provider/thread identities,
numeric RTF and peak-memory values, native Poco identity, and no document-text
field. Reports and generated audio are intentionally not committed. Correctness
parity remains task 4.9; this comparison adds no parity matrix or performance
gate.

## App-private storage layout (task 6.3)

`core/storage/AppPrivateStorage` uses Android `filesDir` as the single private
root. Its stable areas are `sources`, `model-packages`, `canonical-text`,
`covers`, `temporary`, `ready-audio`, and `diagnostics`. Existing proof and
qualification paths remain `typed-proof`, `benchmark-reports`, `parity-input`,
and `parity-reports`; model archives remain `model-packages/active.zip` and
`last-valid.zip`. The API only resolves contained paths; it does not perform
file publication, syncing, checksumming, or cleanup.

Focused task 6.3 verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest :core:lintDebug \
  :core:assembleDebug
```

## Atomic private artifacts (task 6.4)

`core/storage/AtomicArtifactStore` writes through a buffered private temporary
file, flushes and calls `FileDescriptor.sync()` before close, runs the caller's
validator, computes SHA-256, and publishes with `ATOMIC_MOVE`. If the file
system does not support atomic moves, it falls back to a replacing regular
move; callers must use temporary/orphan cleanup during later reconciliation
because that fallback is not power-loss atomic. Cleanup accepts referenced
files from the durable owner and does not access Room itself.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest :core:lintDebug :core:assembleDebug
```

## Content-addressed generation keys (task 6.5)

`core/generation/GenerationKeyCalculator` emits lowercase 64-character
SHA-256 keys. The dependency key canonicalizes verified model/voice identities,
processing versions, lexicographically sorted inference-setting names, and the
audio-processing version. The generation key binds the dependency key to the
ordered `List<Int>` token IDs. Keys are pure calculations and contain no
timestamps or unordered serialization.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest --tests '*GenerationKeyTest' :core:lintDebug :core:assembleDebug
```

## Startup reconciliation (task 6.6)

`core/reconciliation/StartupReconciliation` uses a Room-backed transaction
adapter and the private artifact store. It removes only stale files under
`filesDir/temporary`, returns interrupted `RUNNING`/`GENERATING` work to
`QUEUED`/`PENDING`, and marks missing, checksum-invalid, or stale-provenance
ready segments as `STALE`. It retains all source, model, and ready-audio files
for inspection or later replacement.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest --tests '*StartupReconciliationTest' :core:lintDebug :core:assembleDebug
```

## Project core test coverage (task 6.7)

Task 6.7 adds a real Room in-memory integration test for project/generation
status transitions, relations, and two-pass startup reconciliation. JVM tests
also cover selective two-block key invalidation and failed publication leaving
an existing ready artifact unchanged.

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest :core:connectedDebugAndroidTest \
  :core:lintDebug :core:assembleDebug
```

The command passed on 2026-08-29 with all core JVM tests, four connected
instrumentation tests on `emulator-5554`, lint, and Debug assembly.

## Readium EPUB spike (task 7.2)

The production build intentionally does not depend on Readium yet. The isolated
`readium-spike/` project tests the Maven Central `shared` and `streamer` AARs
against the task-7.1 fixtures:

```sh
python3 document-epub/src/test/resources/fixtures/fixture_tool.py validate
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew -p readium-spike :app:testDebugUnitTest --tests '*ReadiumFixtureTest'
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew -p readium-spike :app:measureReadiumArtifacts :app:assembleDebug
```

The runnable compatibility control is Readium 3.1.0 with the app's Kotlin
2.1.10, AGP 8.8.2, compile SDK 35, min SDK 30, and JDK 21 baseline. Readium
3.3.0 resolves but its AAR metadata requires compile SDK 36. The 3.3.0 source
tag builds `shared` and `streamer` with Readium's Gradle 9.1.0, AGP 9.0.0,
Kotlin 2.3.20, and compile SDK 36. The experiment's size, fixture output,
source-build evidence, and F-Droid disposition are in
`readium-spike/README.md`; no Readium dependency is in the production app yet.

## Private EPUB source import (task 7.4)

`document-epub` exposes `SafEpubSourceRepository.importSelected(Uri)` for a URI
returned by the Android document picker. `ContentResolverEpubSourceReader` opens
that URI once; the repository copies it through `AtomicArtifactStore` into
`temporary/`, uses the staged artifact SHA-256 as the content fingerprint, checks
the `EpubProjectIndex`, and publishes a stable `sources/<projectId>/source.epub`
copy atomically. The original URI is retained in `book_project.source_uri`, while
`source_path` is the only later-use path. No EPUB archive/XML parsing or security
limits are part of this boundary yet.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :document-epub:testDebugUnitTest --tests '*EpubSourceRepositoryTest'
```

## EPUB archive/XML security (task 7.5)

`EpubSecurityValidator` runs on the private temporary source before
fingerprinting, index lookup, or publication. It does not extract ZIP entries
or follow resource references. Strict default thresholds reject 40 or more
entries, 128 KiB or more total uncompressed expansion, 8 KiB or more for one
entry, a 100:1 or greater compression ratio, XML payloads at 64 KiB, and XML
nesting deeper than 64 elements. It also rejects traversal/absolute entry paths,
duplicates, encrypted entries and EPUB DRM marker files, malformed ZIP/XML data, DTD/entity declarations,
external URI attributes, and XML parser configurations that cannot be hardened.

Rejections return a typed `EpubSecurityDiagnostic` without source text or URI;
the temporary source is deleted and no project source/index row is published.
These limits provide bounded inspection only. Canonical Markdown, cleanup
diagnostics, and user-facing import warnings remain later tasks.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :document-epub:testDebugUnitTest --tests '*EpubSecurityValidatorTest' \
  --tests '*EpubSourceRepositoryTest'
```

## EPUB structured IR mapping (task 7.6)

`EpubDocumentParser` consumes only the exact `sources/<projectId>/source.epub`
path published below `AppPrivateStorage`. It reruns `EpubSecurityValidator`
before opening `ZipFile`, then maps OPF metadata, EPUB2 NCX or EPUB3 nav,
cover bytes, spine order, and HTML headings, paragraphs, lists, quotes, poetry,
captions, notes, and scene breaks. Unsupported media, navigation content, empty
chapters, missing spine targets, and the authored EPUB2 double-`OEBPS/` targets
are retained as typed `SKIPPED` IR blocks with stable entry/XPath locators.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :document-epub:testDebugUnitTest --tests '*EpubDocumentParserTest'
```
