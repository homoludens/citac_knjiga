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
duplicates, encrypted entries and EPUB DRM marker files, malformed ZIP/XML data,
DTD/entity declarations, and external URI attributes. Android XML implementations
that do not expose optional JAXP hardening switches remain supported because the
validator rejects DTD/entity markup before parsing, bounds XML inspection, and
installs an external-resource resolver.

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

## Canonical EPUB text (task 7.7)

`EpubMarkdownRenderer` produces stable LF-terminated UTF-8 Markdown. Chapter
and block source locators are retained as deterministic HTML comments; headings,
paragraphs, lists, quotations, poetry, captions, notes, scene breaks, and
skipped blocks have explicit formatting. Skipped blocks with recovered text are
kept in non-narrating comments and produce cleanup warnings rather than being
silently discarded.

`EpubCanonicalTextService.renderAndPersist` writes chapter Markdown through
`AtomicArtifactStore` to `canonical-text/<projectId>/<chapterId>.md` and writes
the actionable warning report to
`diagnostics/<projectId>/import-warnings.json`. It snapshots existing files and
rolls back all newly published artifacts if any chapter or report publication
fails, so a failed import cannot leave partial canonical text.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :document-epub:testDebugUnitTest --tests '*EpubCanonicalTextTest' \
  :document-epub:testDebugUnitTest --tests '*EpubDocumentParserTest'
```

## EPUB import preview (task 7.8)

The preview flow uses the Android `OpenDocument` SAF contract for EPUB/ZIP
sources. `EpubImportPreviewService` keeps a security-validated source under
`temporary/epub-<projectId>/source.epub`, parses it with the direct parser, and
renders canonical Markdown and warnings in memory. No source, Room index row,
canonical chapter, or warning report is published until the user chooses
`Прихвати и увези`; cancel and failed preview paths delete the staging file.
The estimate includes source bytes, UTF-8 canonical text, cover bytes, warning
diagnostics, and a minimum 64 KiB or 10 percent safety margin.

Focused and full verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :document-epub:testDebugUnitTest :core:testDebugUnitTest \
  :app:testStandardDebugUnitTest :app:testFdroidDebugUnitTest \
  :app:lintStandardDebug :app:lintFdroidDebug :core:lintDebug \
  :document-epub:lintDebug :tts-onnx:lintDebug :playback-export:lintDebug \
  :app:assembleStandardDebug :app:assembleStandardRelease \
  :app:assembleFdroidDebug :app:assembleFdroidRelease
```

## EPUB one-chapter vertical proof (task 7.9)

`EpubChapterProofService` is the deliberately bounded bridge from accepted
EPUB preview to one generated chapter. It selects narratable blocks from one
chapter, uses the existing native Serbian preprocessing and direct ONNX Runtime
proof engine, publishes a validated app-private PCM16 WAV atomically, records
the generation/model/voice provenance in Room, and plays the chapter through
the local `AudioTrack` path. It does not add a durable worker, whole-book queue,
Media3, or export behavior.

The repeatable instrumentation proof uses the committed
`document-epub/src/test/resources/fixtures/serbian-epub3.epub` asset and a
locally staged verified model package. The package used on the target device
was `/tmp/citac-knjiga-public-package-20260828/kokoro-serbian-dragana-v2.zip`
with SHA-256
`58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`.
The package and generated WAV are not repository artifacts.

On the Poco F3 (`Xiaomi M2012K11AG`, API 33, native `arm64-v8a`), disable
networking explicitly, build and install both the application and
instrumentation APKs, then run:

```sh
adb -s 2555a240 shell svc wifi disable
adb -s 2555a240 shell svc data disable
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest
adb -s 2555a240 push app/build/outputs/apk/standard/debug/app-standard-debug.apk /data/local/tmp/task-7-9-app.apk
adb -s 2555a240 shell pm install -r -d /data/local/tmp/task-7-9-app.apk
adb -s 2555a240 push app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk /data/local/tmp/task-7-9-test.apk
adb -s 2555a240 shell pm install -r -d /data/local/tmp/task-7-9-test.apk
adb -s 2555a240 shell am instrument -w -r \
  -e class com.homoludens.citacknjiga.proof.EpubChapterProofAndroidTest#knownEpubChapterGeneratesAndPlaysOffline \
  com.homoludens.citacknjiga.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The 2026-08-30 run completed with `OK (1 test)`. It validated EPUB import,
accepted publication, one extracted chapter generation, Room `READY` segment
state, 24 kHz mono PCM16 WAV publication, and local playback while networking
was disabled.

## Durable generation state (task 8.2)

`core/generation/GenerationStateValidator` declares the allowed project,
chapter, audio-segment, and generation-run transitions. `GenerationStateService`
validates ownership and parent prerequisites, then persists each change in a
Room transaction using conditional DAO updates. Starting a run or segment
increments its attempt count; failures require a non-blank `GenerationError`
and are stored in the existing `last_error` columns. Retry keeps the last
actionable error until successful completion. This task does not add a schema
migration, coroutine runner, WorkManager scheduling, or audio validation.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :core:testDebugUnitTest :core:connectedDebugAndroidTest \
  :core:lintDebug :core:assembleDebug
```

## Bounded generation runner (task 8.3)

`core/generation/BoundedGenerationRunner` executes a queued Room run one segment
at a time. `GenerationStateService` conditionally claims the lowest sequence
pending segment in a transaction; the injected suspend generator supplies the
bounded TTS output and validator. `AtomicArtifactStore` validates and publishes
the artifact before the state service records ready status and full provenance.
Pause/cancel state is checked before each claim, so the current segment may
finish but no next segment starts. Coroutine cancellation releases a generating
segment back to `PENDING`; ordinary failures record an actionable error and keep
the segment retryable. Scheduling, notifications, recovery hosts, playback, and
export remain outside this task.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest --tests '*BoundedGenerationRunnerTest'
```

## WorkManager generation scheduling (task 8.4)

`core/generation/GenerationWorkScheduler` is the WorkManager adapter for the
durable generation queue. It calls `RoomGenerationQueue.reconcile()` before
enqueueing work, so interrupted `RUNNING` runs and `GENERATING` segments are
returned to queued/pending states by `StartupReconciliation`. It re-enqueues
queued runs using the stable name `generation-run-<run-id>` and
`ExistingWorkPolicy.KEEP`; WorkManager therefore survives process death, reboot,
and application update without duplicate run chains. Paused or cancelled runs
are not automatically re-enqueued.

Each request requires no network, a non-low battery, and non-low storage. An
unexpected worker exception uses exponential WorkManager backoff; a durable
failed run returns failure with its failed segment IDs so the persisted retry
action remains authoritative. The worker does not call `setForeground()` or
create notifications unless a task-8.5 notification controller is supplied. The
app composition root must provide the same `GenerationRunExecutor` through
`GenerationWorkerFactory` when wiring the production model generator.

Focused verification and Android-test compilation:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :core:testDebugUnitTest :core:compileDebugAndroidTestKotlin \
  :core:lintDebug :core:assembleDebug
```

`GenerationWorkSchedulerTest` requires a connected Android device for execution.
The available Poco F3 is Android 13; Android 16 execution-host qualification is
explicitly deferred until an API 36 device or emulator is available.

## Foreground generation notification (task 8.5)

`GenerationNotificationController` creates the low-importance generation
channel and builds a `ForegroundInfo` from Room-derived progress. The
notification includes the book title, ready/total segment progress, failed
segment count, and pause/resume/cancel actions. `GenerationWorker` refreshes
foreground state and WorkManager progress once per second while the injected
bounded executor runs. The action receiver persists pause/resume/cancel state
through `GenerationStateService`; resume then re-enqueues the same stable unique
work name. It does not change queue semantics or select the Android execution
host.

The app declares `POST_NOTIFICATIONS` and `FOREGROUND_SERVICE`. Runtime
notification permission UX and Android 16 WorkManager versus direct-service
qualification remain deferred post-MVP work.

## Audio validation and selective retry (task 8.7)

`OnnxAudioOutputValidator` remains the tensor boundary gate for finite,
non-silent, unclipped 24 kHz mono output with a plausible duration and exact
sample count. `AtomicArtifactStore` runs the generated-file validator before
hashing or replacing the ready artifact, so invalid audio and failed writes
cannot publish over an existing segment.

`GenerationFailurePolicy` persists stable `AUDIO_*`, `INFERENCE_FAILURE`,
`PROVENANCE_MISMATCH`, and `WRITE_FAILURE` codes in the existing segment error
field. Transient audio-validation, inference, and write failures are retried
up to three total segment attempts; provenance failures are not retried.
Attempt counts remain in Room. Reconciliation can receive the expected key for
each segment and marks only ready segments with stale keys, provenance, or file
integrity as `STALE`; unaffected verified ready segments remain reusable.

## Generation storage safeguards (task 8.8)

`core/storage/GenerationStoragePolicy` estimates the largest temporary artifact
and the total requested ready-audio bytes before generation. Its default safety
margin is 10% with a 64 KiB minimum, and capacity is read from the private
`filesDir` filesystem. The bounded runner can receive the same per-segment
estimates: it refuses a queued run before changing Room state, then rechecks
capacity before and after each segment. A capacity drop records
`INSUFFICIENT_STORAGE` on the generation run while preserving completed ready
segments and pending work.

`AtomicArtifactStore` maps ENOSPC-style temporary/publication failures to the
non-retryable `STORAGE` category; all other write failures remain
`WRITE_FAILURE`. `GenerationStorageCleanup` requires an explicit choice between
stale temporary files, orphan ready audio, or no cleanup. It never removes
private source documents, canonical project metadata, or Room data.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest --tests '*GenerationStoragePolicyTest' \
  --tests '*BoundedGenerationRunnerTest'
```

## Recovery qualification (task 8.9)

Recovery tests use deterministic fault injection and persisted crash snapshots;
they do not kill the test process. The JVM suite covers inference, temporary
write, and publication interruption, retryable publication failure, stale
temporary cleanup, low-capacity storage, and an unavailable SAF source. The
Android suite repeats the generation cases with a file-backed Room database,
closes and reopens it before `StartupReconciliation`, and verifies that ready
audio, private source files, Room state, and queued retry work survive. The
source-provider instrumentation test verifies that an unavailable URI publishes
neither source nor project state.

Focused JVM and Android commands:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :core:testDebugUnitTest :document-epub:testDebugUnitTest \
  :core:compileDebugAndroidTestKotlin \
  :document-epub:compileDebugAndroidTestKotlin \
  :core:connectedDebugAndroidTest \
  :document-epub:connectedDebugAndroidTest
```

The available `emulator-5554` API 35 x86_64 device passed the core recovery
suite and the source-provider instrumentation suite. True `adb` force-stop or
kill during inference/write/publication, physical reboot, application update,
and real device low-space exhaustion were not run. The tests therefore prove
the persisted reconciliation and fault-handling contracts, not those device
operations. Portable export is not implemented yet (task 10), so export
destination disappearance/capacity instrumentation is explicitly unexecuted
and no export proof is claimed here.

## Multi-chapter resume demonstration (task 8.10)

`MultiChapterResumeAndroidTest` uses the self-authored
`document-epub/src/test/resources/fixtures/serbian-epub3.epub` fixture. It
accepts and parses its two spine-ordered chapters, creates four deterministic
segment rows, starts the bounded runner, and holds the second segment after the
first segment has been atomically published and checked into Room. The test
then safely stops the coroutine, reopens Room, injects the persisted
`RUNNING`/`GENERATING` crash snapshot that an abrupt process death would leave,
closes/reopens the database again, and runs `StartupReconciliation`. Resume is
asserted to generate exactly the three pending segment IDs. The first segment's
private file path, bytes, and SHA-256 are compared before and after resume.

Run the deterministic instrumentation proof with the attached emulator:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.generation.MultiChapterResumeAndroidTest
```

This is a Room/filesystem recovery demonstration, not production audio
qualification: its `SegmentGenerator` writes deterministic test bytes through
the normal atomic artifact path and does not load the model package or ONNX
runtime. The available target was API 35 x86_64 `emulator-5554`; no Poco F3 was
attached, and no `adb force-stop`, kill, physical reboot, package update, or
production model inference was run for this task. Therefore the test does not
claim real force-stop proof or Poco evidence. The test cleans its unique Room
database, private source, canonical text, diagnostics, and ready-audio files in
`finally`; it creates no committed WAV, model package, report, or export.

## Media3 player controls (task 9.3)

`playback-export` keeps `AudiobookPlaybackService` as the only ExoPlayer owner.
`AudiobookPlayerController` connects one Media3 `MediaController` to that
service, observes verified Room-ready audio, and exposes play/pause, clamped
seek and jumps, completed-chapter navigation/selection, and playback speed.
Jump values are held in memory and limited to 1-120 seconds; supported speeds
are 0.75x, 1x, 1.25x, 1.5x, and 2x. The queue and catalog remain snapshots until
the later dynamic-queue task.

Focused and full verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :playback-export:testDebugUnitTest \
  :app:testStandardDebugUnitTest :app:testFdroidDebugUnitTest \
  :app:compileStandardDebugAndroidTestKotlin \
  :app:compileFdroidDebugAndroidTestKotlin
```

The focused JVM and Compose instrumentation control tests pass on the API 35
emulator. A full app instrumentation run remains blocked by the pre-existing
task-4.10 test when no verified model package is staged; model packages are
intentionally not repository artifacts.

## Playback position persistence (task 9.4)

`PlaybackPositionPersistence` uses the existing Room `playback_position` row;
no schema migration or DataStore state is added. The Media3 service restores
the saved book's ready chapter and segment before starting playback, clamps
the saved position to the current item duration, and falls back to the first
available segment at position zero when the target is no longer available.
Unsupported or non-finite speeds use `1.0x`. While attached, the adapter polls
the player every second and writes at most once per two seconds, while player
events and service teardown provide additional persistence opportunities.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :playback-export:testDebugUnitTest \
  :playback-export:connectedDebugAndroidTest
```

The connected suite includes a file-backed Room close/reopen restore test on
the available API 35 x86_64 emulator. Task 9.4 did not add notification,
lock-screen, headset/audio-focus/interruption, dynamic queue, missing-audio,
or demonstration behavior.

## Media system integration (task 9.5)

`AudiobookPlaybackService` uses Media3's `DefaultMediaNotificationProvider` once,
with channel `audiobook_playback` and notification ID `4101`. Media items expose
chapter title, book title, author, and audiobook-chapter type; the notification
opens the app and offers standard playback plus 15-second back/30-second forward
actions. `ExoPlayer` is configured with speech audio attributes and
`handleAudioFocus=true`, so Media3 requests and abandons platform focus. Speech
content pauses for transient and ducking losses and resumes on focus gain only if
it was playing before the interruption. Permanent loss and
`AUDIO_BECOMING_NOISY` do not resume automatically. The MediaSession service is
the sole lock-screen, headset, and Bluetooth command surface.

Focused checks:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:lintDebug
```

## Dynamic playback queues (task 9.6)

`PlaybackQueueCoordinator` is created by `AudiobookPlaybackService` and observes
`ReadyAudioRepository` for the active project. Only artifacts that passed the
existing private-path, format, size, and SHA-256 checks enter the Media3 queue.
The coordinator orders by chapter ordinal, segment sequence, and segment ID,
deduplicates segment IDs deterministically, and preserves the active item ID and
position when inserting or reordering items. It leaves active playback running;
an update that would remove the playing item is held until a player event reaches
a safe boundary or playback is stopped. Generation state remains in Room and
outside the player.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:connectedDebugAndroidTest \
  :playback-export:lintDebug
```

The focused JVM suite covers ordering, deduplication, Room-ready additions,
active position preservation, insertion before the active item, and deferred
removal timing. The connected Media3 suite covers the queue adapter on the API 35
x86_64 emulator.

## Unavailable playback audio (task 9.7)

`PlaybackAvailabilityPolicy` rejects non-ready, missing, private-path-invalid,
size/checksum-invalid, malformed/unreadable, stale-key, and stale-provenance
segments before they reach Media3. `RoomPlaybackValidationContextSource`
compares ready segments with the active model package and generation runs;
callers may also provide expected generation keys. The repository reports
issues without mutating Room. The controller exposes the issue and delegates
`generation/retry/<segment>` to an injected generation callback.

The deterministic queue rule is: a newly unavailable current item is paused and
the next valid item in the previous queue is selected and resumed; when no next
item exists, the queue is rebuilt paused. A normal queue update that merely
removes a not-current item still waits for the existing safe boundary behavior.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  ./gradlew :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:lintDebug
```

The progressive-playback demonstration remains task 9.8.
