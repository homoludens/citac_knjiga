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
explicitly configured variant with CPU fallback. NNAPI is deferred to task 5.3.

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

`tts-onnx` provides `AndroidDeviceParityRunner` and
`DeviceParityReportStore`. The runner compares caller-supplied desktop ONNX
waveforms with Android `OnnxTtsOutput` using every measurement in the immutable
`fp32-parity-v1` declaration: exact sample count, waveform error, STFT cosine,
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

With no verified package it persists `status: "blocked"` and `ok: false`. The
current connected test is fixture evidence only and uses the API 35 x86_64
emulator:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :tts-onnx:testDebugUnitTest :tts-onnx:connectedDebugAndroidTest
```

Task 4.9 is not qualified. The legal release gate blocks the production model
package and derived audio, the checked-in desktop parity report retains metrics
but no raw ONNX waveforms for Android, and the available emulator is x86_64.
The local ignored `model-tools/export/dragana.onnx` is not production evidence.
The next step is to obtain legal clearance for a private test package, publish
the matching desktop ONNX vector waveforms without document text, and run the
full declared vector set on a native ARM64 Android device or emulator. The
existing desktop 26-vector report also has the recorded frozen-threshold
failures for `input-limit-at` and `paragraph-no-sentence-boundary`; those must
be resolved without weakening `fp32-parity-v1` before a passing device report.
