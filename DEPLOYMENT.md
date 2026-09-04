# Deployment & Environment — citac_knjiga

## PDF import verification

The selected production parser is
`com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0). Its locked crypto
runtime closure is `org.bouncycastle:bcprov-jdk15to18:1.72`,
`org.bouncycastle:bcpkix-jdk15to18:1.72`, and
`org.bouncycastle:bcutil-jdk15to18:1.72` (Bouncy Castle MIT-like license).
The PdfBox AAR SHA-256 is
`30277f879cfd571db2a137582c95516a0d4ea6778e945519bc58ca93d57d88c7`; all
transitive artifact hashes are recorded in
`document-pdf/pdfbox-source-closure.json` and
`pdf-qualification/android-consumer/qualification-closure.json`.

The checked-in qualification report is
`pdf-qualification/qualification-report.json` and is `pass`. Its gating
scope is API 33 `arm64-v8a` on the Poco F3 production target and API 35
`x86_64` on the Google development emulator. API 30 and API 36 are explicitly
non-gating and were not executed. The report enables the production gate;
`PdfFeatureAvailability.QUALIFIED` is derived from that passing report and the
PDF services are constructed only when it is true.

The release evidence covers:

- `./gradlew --offline :document-pdf:testDebugUnitTest`: JVM PDF coverage passes (13 tests), including limits, geometry, diagnostics, provenance, and atomic rollback.
- `./gradlew --offline :document-pdf:connectedDebugAndroidTest`: the 10-test PdfBox suite passes on both Poco F3 API 33 `arm64-v8a` and API 35 `x86_64`, covering resource-loader setup, real extraction, provider disappearance after staging, cancellation cleanup, malformed/protected/truncated/image-only files, external-resource isolation, and the enabled gate.
- Accepted PDF persistence is covered through Room project/chapter/narration-block projection with no generation run or audio segment created during acceptance.
- The focused app PDF UI suite passes 4/4 on API 35 `x86_64`, covering invalid ranges, preview-before-acceptance, loading cancellation, safe diagnostics, and accepted state without generation. The API 33 app UI attempt lost device transport before completion; no API 33 app UI pass is claimed.
- `python3 scripts/check_pdf_qualification.py`, dependency locking/checksum verification, `python3 scripts/check_source_closure.py`, the PdfBox source/license closure, and Apache/Bouncy Castle notices pass.
- Offline production compile, `document-pdf` lint, and standard/F-Droid release APK assembly pass with the parser enabled; release payload checks contain no OCR model, PDF upload path, or undeclared PDF dependency. The release APKs are unsigned: this checkout has no production keystore or signing credentials, so unsigned APK inspection is not signed-release evidence.

Before release, run the deterministic checks and the locked build:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
python3 scripts/check_pdf_qualification.py
python3 scripts/check_source_closure.py
python3 scripts/audit_dependencies.py
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew --offline \
  :app:compileStandardDebugKotlin \
  :document-pdf:testDebugUnitTest \
  :document-pdf:connectedDebugAndroidTest \
  :document-pdf:lintDebug \
  :app:assembleStandardRelease :app:assembleFdroidRelease
```

If the parser fails after release, restore the qualification report to a
non-passing result so `PdfFeatureAvailability` is disabled and the app keeps
the unavailable diagnostic. Remove only abandoned PDF staging directories
under the private `temporary/pdf-*` area (the existing
`PdfOrphanReconciler` path). Do not remove accepted PDF sources, canonical
text, Room projects, EPUB data, model packages, or verified audio.

## EPUB import verification

The affected-module gate for `fix-real-world-epub-import` is:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :document-epub:testDebugUnitTest \
  :document-epub:lintDebug \
  :document-epub:assembleRelease \
  :app:lintStandardDebug \
  :app:assembleRelease
```

Document-EPUB connected tests require an attached API 35 emulator or device:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :document-epub:connectedDebugAndroidTest
```

The complete app connected suite also contains a separate typed-text proof that
requires a verified private model package; its absence is not an EPUB import
failure.

## Generation troubleshooting

Accepted EPUB and PDF imports now enqueue the same durable whole-book
generation request. Chapter/book regeneration uses that same queue and the
currently selected engine; no document-format-specific generation path is
required.

Durable generation progress is committed when a complete audio segment is
published. During the active segment, Kokoro's existing inference chunks and
VITS text chunks of approximately 180 characters append to a valid cumulative
PCM16 WAV under private temporary storage. The library combines completed
segment words with this active chunk checkpoint and shows the staging WAV size
beside the progress bar. The bar remains indeterminate until the first chunk
finishes because a native inference call cannot expose finer progress. The WAV
size proves that chunk output is accumulating; it is not an estimate of the
final duration or completion percentage. Publication, cancellation, and
terminal inference failures remove the active checkpoint and staging WAV.

If both EPUB and PDF projects show the generic generation failure, inspect the
persisted generation run's `last_error` before changing document import code.
The shared VITS frontend previously rejected common book characters such as
U+2014, U+00E0, U+00F6, U+00BB, and U+00AB before inference. It now normalizes
those punctuation and accent forms to the verified model vocabulary while
continuing to reject digits and genuinely unknown symbols. Verify the change
with:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew --offline :tts-onnx:testDebugUnitTest
```

VITS JNI is included in the default APK build. On its first online build,
`prepareSherpaVitsRuntime` fetches the pinned Sherpa-ONNX revision and extracts
the required headers and ABI libraries from the resolved ONNX Runtime Android
AAR into `tts-onnx/build`. The model package remains a separate verified
download/import. The debug runtime supports both the API 35 `x86_64` emulator
and the production `arm64-v8a` target:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:assembleStandardDebug
```

Use `-PenableSherpaVits=false` to omit the VITS JNI bridge. Existing external
source/runtime overrides remain available through `sherpaOnnxSourceDir`,
`sherpaOnnxRuntimeLibRoot`, and `sherpaOnnxRuntimeIncludeDir`. After a model
download finishes, diagnostics refreshes the installed package and engine list
without requiring navigation away from the screen.

## Signed app release artifacts (task 12.7)

`scripts/release_artifacts.py` is the app-only release gate. It builds the
locked `standardRelease` and `fdroidRelease` variants with strict Gradle
dependency verification, then checks package/version metadata, source closure,
F-Droid policy, release documentation, APK payloads, and common secret
material. The output directory may contain only:

```text
citac-knjiga-standard-v0.1.0.apk
citac-knjiga-fdroid-v0.1.0-fdroid.apk
SHA256SUMS
release-manifest.json
```

The manifest records the Git commit, clean-tree state, toolchain/input hashes,
package IDs/version codes/version names, APK checksums, signing schemes, and
the signing certificate SHA-256/DN. Model packages, generated audio, and
secrets are explicitly recorded as excluded. It also records the exact
task-4.2 model-download network policy from source closure; verification rejects
any stale or broadened policy. Model packages remain separately
imported app-private ZIPs and are never a build input or release output.

The GitHub workflow `.github/workflows/release.yml` runs on `v*` tags or manual
dispatch. It creates a temporary `0600` keystore from the
`ANDROID_RELEASE_KEYSTORE_B64` GitHub secret, uses the other three
`ANDROID_RELEASE_*` secrets through environment references, signs with the
pinned Android build-tools `apksigner`, verifies v2/v3 and certificate
identity, and uploads only the verified app artifact directory. It does not
create or publish a GitHub Release and does not publish model packages.

For a configured external keystore, use a path outside the repository:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_KEY_ALIAS=release \
ANDROID_KEYSTORE_PASSWORD='provided-out-of-band' \
ANDROID_KEY_PASSWORD='provided-out-of-band' \
python3 scripts/release_artifacts.py build \
  --keystore /secure/path/citac-knjiga-release.jks \
  --output-dir /tmp/citac-knjiga-release-artifacts \
  --require-clean
```

Without credentials, an explicitly local-only build can be inspected as
clearly labeled unsigned output:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
python3 scripts/release_artifacts.py build --unsigned \
  --output-dir /tmp/citac-knjiga-unsigned-artifacts
python3 scripts/release_artifacts.py verify \
  --artifact-dir /tmp/citac-knjiga-unsigned-artifacts --allow-unsigned
```

The unsigned path is not release evidence. This checkout has no production
keystore, signing credentials, or GitHub secret-backed workflow run, so task
12.7 remains blocked and no fake signature has been generated.

## Final publication gate (task 12.8)

`scripts/check_release_gate.py` is a read-only pre-publication check. It does
not build, sign, upload, publish, create a GitHub release, or handle signing
credentials. It exits non-zero unless every hard gate is `PASS`; task 12.8 is
not used as proof of its own completion.

Run it from the repository root:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  python3 scripts/check_release_gate.py
```

For a future evaluation, supply only externally staged evidence and app-only
signed artifacts. The artifact directory must be outside this repository:

```sh
python3 scripts/check_release_gate.py \
  --artifact-dir /tmp/citac-knjiga-release-artifacts \
  --model-manifest /secure/path/cleared-model-manifest.json \
  --android-parity-report /secure/path/device-parity-report.json \
  --json /tmp/citac-knjiga-release-gate.json
```

The hard gates cover signed app artifacts, model legal clearance, desktop and
Android parity, real production-model proof, two-player external portability,
the Android 11/current/Android 16/Poco matrix, the task 11.8 capability audit,
dependency/privacy/F-Droid checks, recovery/export/instrumentation evidence,
OpenSpec strict validation, and app/model/audio artifact separation. Optional
model packages and generated audio are never release inputs or app artifacts.
The benchmark RTF, memory, thermal, and battery measurements are reported as
`INFO` only and do not replace the hard device qualification gate.

Current evaluation result: **publication refused**. Task 12.7 has no signed
artifact evidence, the public model-weight legal gate is open, task 10.8 has
no two external players, task 11.4 lacks the required device matrix, and the
task 11.8 audit says the release candidate is not ready. Keep the generated
gate JSON and all model/audio artifacts outside the repository.

## Serbian VITS qualification

The independent qualification record is under
`reports/serbian-vits-qualification/`. It evaluates only
`daremc86/sr-cv-vits` revision
`83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`, Dragana speaker `0`, native
22,050 Hz, and final 24,000 Hz mono. Its exact outcome remains **REJECTED**:
the legal gate is **ALLOWED** by project-maintainer confirmation and the
deterministic conversion/graph gates pass, but production Android generation,
parity, Serbian quality, and API 33 device evidence remain unresolved. No VITS
package or payload is embedded in either Android variant. The conditional VITS
runtime boundary remains unavailable until the API 33 gate passes, so Kokoro
remains the only usable production engine.

Run the redacted record checks with the locked desktop environment:

```sh
model-tools/.venv/bin/python model-tools/scripts/validate_serbian_vits_qualification.py
model-tools/.venv/bin/python model-tools/scripts/check_serbian_vits_evidence.py
model-tools/.venv/bin/python -m pytest model-tools/tests/test_serbian_vits_qualification.py
```

Raw Hugging Face source, checkpoints, ONNX output, packages, numeric sidecars,
and generated audio belong outside the repository. The validated external
package uses `serbian-vits-model-package:1`, self-contained ONNX, declared
entries only, and local offline inference. The exact native-to-final resampler is
versioned as `serbian-vits-resampler-v1` and may run once only; invalid samples
are rejected before PCM or codec publication. Failed package import or
generation leaves the last valid Kokoro package and existing audio untouched.

The implementation keeps VITS in `vits-active.zip` and `vits-last-valid.zip`,
separate from Kokoro's `active.zip` and `last-valid.zip`. A package must carry
the exact model revision, CC-BY-4.0 attribution and modification notice,
Sherpa-compatible entries, and a `PASS` API 33 `arm64-v8a` qualification before
the engine selector exposes it. The external package manifest and conversion
record satisfy the package contract; the full qualification run is still
pending. The development API 33 fixture uses `x86_64`; API 30/35/36 are
explicitly non-gating for this change. Sherpa source closure
and Apache-2.0 notice are recorded in
`model-tools/native/sherpa-onnx-source-closure-v1.json` and
`model-tools/native/SHERPA-NOTICE.md`.

Default builds fetch the pinned Sherpa JNI source and extract the ONNX Runtime
JNI/headers from the declared Android AAR. The source checkout and AAR contents
remain build outputs; the model package and generated audio remain outside this
repository:

```sh
ANDROID_HOME=/path/to/android-sdk \
ANDROID_SDK_ROOT=/path/to/android-sdk \
./gradlew :app:assembleStandardRelease :app:assembleFdroidRelease
```

The selected engine checks that `libcita_sherpa_vits.so` loads and still
requires a verified VITS package before exposing the engine.

The first real Sherpa VITS Android smoke run passed on Poco F3 `2555a240`
(`M2012K11AG`, API 33, native `arm64-v8a`) with Wi-Fi and mobile data disabled.
`SherpaVitsAndroidTest#qualifiedPackageGeneratesOfflineSerbianAudio` imported
the external package, generated non-silent native 22,050 Hz mono audio, and
validated the single 24,000 Hz mono resampling step. The package SHA-256 was
`45aa231e12c8a317f0d093cfb56d54066e19b53561b4ac401661109f19abe5dc`.
This is recorded in `android-parity-report.json` and
`android-matrix-report.json` as smoke evidence only. Full parity, resource,
interruption, recovery, and equivalent API 33 `x86_64` evidence remain open;
the overall qualification therefore remains rejected and VITS is not exposed
as a usable production engine.

The disposable fetch command is:

```sh
python3 model-tools/scripts/fetch_serbian_vits_source.py \
  /tmp/citac-knjiga-sr-cv-vits-83dc1e1b \
  --manifest /tmp/citac-knjiga-sr-cv-vits-source-manifest.json
```

The fetched checkpoint is never an Android input. The verified source manifest
records `model.pth` SHA-256
`7b43231864dbac69901155ed397c1a30d0d06b066b03f0348fd39eed2ea1d4b0`,
`config.json` SHA-256
`a0a14385a21854b970cee364a853950aa6168e690beb8c7c35d1673ea042d5c8`,
`speaker_ids.json` SHA-256
`4d877c09f8dca306307c51a1d0070d5fc493615eed11fdadc7d55d2976f685c8`, and
`language_ids.json` SHA-256
`d249880c338370db1ee4df26207f541512cd05c275f2c0aafedf27e20abeccf4`.
The model source and license references are
`https://huggingface.co/daremc86/sr-cv-vits/tree/83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`
and `https://creativecommons.org/licenses/by/4.0/`. Sherpa-ONNX is sourced
from `https://github.com/k2-fsa/sherpa-onnx` under
`https://www.apache.org/licenses/LICENSE-2.0`.

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

## Native/runtime source closure (task 12.4)

The native Android phonemizer is not a checked-in `.so` or AAR. The only
application native source is `tts-onnx/src/main/cpp/native_espeak.cpp`, which
links eSpeak-NG source from the exact upstream commit below. There are no local
patches:

| Component | Repository/source | Revision or checksum | License/build treatment |
|---|---|---|---|
| eSpeak-NG engine and data generator | `https://github.com/espeak-ng/espeak-ng.git` | tag `1.52.0`, commit `4870adfa25b1a32b4361592f1be8a40337c58d6c` | GPL-3.0-or-later; source-built |
| Serbian JNI bridge | this repository, `tts-onnx/src/main/cpp/native_espeak.cpp` | no local patch | linked into generated `libcita_espeak.so` |
| ONNX Runtime Android | Maven Central `com.microsoft.onnxruntime:onnxruntime-android:1.29.0` | AAR SHA-256 `e97540ca78fe36f6fe2013f82843414fb843b6c7681fb04644cba5e1406662dd`; upstream tag `v1.29.0`, commit `2e2543fbe9fae542f921d47a72d21d5a4ef0b710` | explicitly declared external Maven dependency, not a checked-in binary |

The complete machine-readable record is
`model-tools/native/source-closure-v1.json`. The seven checked-in data files
are generated outputs from the pinned eSpeak-NG host `data` target, not a
replacement phonemizer. Their size and SHA-256 values remain in
`model-tools/native/espeak-data-manifest-v1.json`; no other generated language
data is copied into the APK.

### Reproduce the eSpeak data closure

Use the locked CMake `3.22.1` and a source checkout outside the repository. The
checkout must be clean and must resolve to the recorded commit:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ESPEAK_SRC=/tmp/citac-knjiga-espeak-ng
export ESPEAK_HOST_BUILD=/tmp/citac-knjiga-espeak-host
export CMAKE="$ANDROID_HOME/cmake/3.22.1/bin/cmake"

git clone --filter=blob:none https://github.com/espeak-ng/espeak-ng.git "$ESPEAK_SRC"
git -C "$ESPEAK_SRC" fetch --depth=1 origin 4870adfa25b1a32b4361592f1be8a40337c58d6c
git -C "$ESPEAK_SRC" checkout --detach 4870adfa25b1a32b4361592f1be8a40337c58d6c
test "$(git -C "$ESPEAK_SRC" rev-parse HEAD)" = 4870adfa25b1a32b4361592f1be8a40337c58d6c
test -z "$(git -C "$ESPEAK_SRC" status --porcelain)"

"$CMAKE" -S "$ESPEAK_SRC" -B "$ESPEAK_HOST_BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DUSE_ASYNC=OFF -DUSE_KLATT=OFF -DUSE_LIBPCAUDIO=OFF \
  -DUSE_LIBSONIC=OFF -DUSE_MBROLA=OFF -DUSE_SPEECHPLAYER=OFF
"$CMAKE" --build "$ESPEAK_HOST_BUILD" --target data --parallel 2

for file in phondata phondata-manifest phonindex phontab intonations sr_dict; do
  install -D "$ESPEAK_HOST_BUILD/espeak-ng-data/$file" \
    "tts-onnx/src/main/assets/espeak-ng-data/$file"
done
install -D "$ESPEAK_HOST_BUILD/espeak-ng-data/lang/zls/sr" \
  tts-onnx/src/main/assets/espeak-ng-data/lang/zls/sr
```

The generated files must match the manifest. The closure check below performs
the exact size/hash and no-extra-file check. The host build creates all
language dictionaries in its temporary output; only the seven declared files
are application inputs.

### Reproduce the Android native library

The direct CMake recipe uses the source checkout above and disables all
FetchContent downloads. It creates the JNI library plus temporary static
archives; none may be committed:

```sh
export NDK="$ANDROID_HOME/ndk/26.1.10909125"
export ESPEAK_ANDROID_BUILD=/tmp/citac-knjiga-espeak-android

"$CMAKE" -S tts-onnx/src/main/cpp -B "$ESPEAK_ANDROID_BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release \
  -DESPEAK_NG_SOURCE_DIR="$ESPEAK_SRC" \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON
"$CMAKE" --build "$ESPEAK_ANDROID_BUILD" --target cita_espeak --parallel 2

file "$ESPEAK_ANDROID_BUILD/libcita_espeak.so"
sha256sum "$ESPEAK_ANDROID_BUILD/libcita_espeak.so"
```

The task-12.4 direct build produced an ARM64 Android 30 ELF with SHA-256
`bb9a8f2b722de5d4dae35f5ab0d40e25007c155516da82c8f032dbd586553092`.
Unstripped native checksums are observations because debug paths and linker
metadata can change the bytes; source commit, flags, toolchain, ABI, and the
data-file hashes are the reproducible provenance contract. Gradle invokes the
same CMake project for `tts-onnx`; release packages only `arm64-v8a`, while
debug packages `x86_64` and `arm64-v8a` for the available emulator and target
device.

### Runtime and model boundary

The F-Droid flavor uses the same source-built `tts-onnx` module and has no
flavor-specific `jniLibs`, file dependency, native archive, model graph, voice
archive, or model package. The closure check permits only the explicitly
documented Gradle Wrapper JAR in the repository and the locked Maven ONNX
Runtime AAR outside the repository. The AAR's native libraries are therefore
declared dependency inputs, not undeclared checked-in binaries.

The ONNX graph and Dragana voice remain user-imported model-package artifacts.
`ModelPackageStore` verifies the package manifest, compatibility, sizes, and
SHA-256 values before inference; model packages are not downloaded, generated,
or bundled by either Android flavor.

Run the deterministic audit from the repository root:

```sh
python3 scripts/check_source_closure.py
```

It scans Android source roots, ignores only named Gradle/CMake/generated output
directories, rejects unexpected native/prebuilt/model files, verifies the
seven-file data closure, and cross-checks the exact Maven coordinate against
the version catalog, dependency lock, and strict verification metadata.

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

## Android AAC/M4A benchmark (task 10.1)

`AndroidAacBenchmarkRunner` is a separate, opt-in platform-codec harness. It
does not load a model, require a model package, or change production playback
or export behavior. `AacBenchmarkFixture` creates the same deterministic 24 kHz
mono PCM16 input on every run: eight 0.5-second synthetic windows labelled for
`s`, `z`, `š`, `ž`, `č`, `ć`, `đ`, and `lj/nj/dž`. The full four-second input and
each independently bounded segment are encoded with Android `MediaCodec` and
`MediaMuxer` as AAC-LC/M4A at 64, 80, and 96 kbps by default. The harness records
codec availability/name, encode elapsed time, output size, MediaExtractor track
duration, and positive gap/trim/drift at the eight segment boundaries. Scratch
WAV/M4A files remain in app cache and are deleted before the JSON report is
published; the host report and instrumentation log default to `/tmp`.

The reproducible command is:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
DEVICE=emulator-5554 \
OUTPUT=/tmp/citac-knjiga-android-aac-benchmark-report.json \
scripts/run_android_aac_benchmark.sh
```

The script disables Wi-Fi/mobile data for the run, restores their prior state,
and returns exit code 3 when no requested platform bitrate is available. Use
`AAC_BITRATES_BPS=64000,80000,96000` to override the default list. This is a
measurement harness only; it makes no bitrate, grouping, fallback, silence, or
PCM cleanup decision for task 10.2.

### Captured emulator run

On 2026-08-30, the available API 35 Google x86_64 emulator (`sdk_gphone64_x86_64`,
`emu64xa`) reported `c2.android.aac.encoder` available at all three rates. The
fixture identity was `serbian-consonants-synthetic-v1`, 96,000 samples,
4,000,000 microseconds, PCM SHA-256
`ff31007a018fb9019096c2cc4a19dcfda9624e773e2a3566c7bb7750aea96649`, and WAV
SHA-256 `2fb8f5e8e69b400a96459eae78bf9818e609e721f65a42e5be1a69ab5573068e`.

| Requested bitrate | M4A size | Encoded duration | Encode time | Boundary gap | Boundary trim | Max drift |
|---:|---:|---:|---:|---:|---:|---:|
| 64 kbps | 35,123 B | 3.968 s | 1,026 ms | 0 us | 245,336 us | 30,667 us |
| 80 kbps | 42,970 B | 3.968 s | 981 ms | 0 us | 245,336 us | 30,667 us |
| 96 kbps | 50,855 B | 3.968 s | 984 ms | 0 us | 245,336 us | 30,667 us |

Quality evidence decodes each full M4A, aligns decoded PCM to the WAV reference
by maximum normalized correlation within +/-4,096 samples, and reports RMS
ratio plus zero-crossing rate for each labelled window. The run aligned at
2,048 samples and produced measurements for all eight windows. The report keeps
manual quality status as `manual_listening_pending`: for natural Serbian
speech, listen to matched WAV/M4A windows in randomized A/B order and record a
1-5 score for consonant identity, sibilant sharpness, affricate attack, and
boundary clicks. The synthetic fixture is useful for repeatable codec stress,
but cannot establish phoneme intelligibility or substitute for a later natural
Serbian speech evaluation. The emulator result is not a Poco F3 ARM64 result
and does not select the final MVP bitrate.

## MVP audio policy (task 10.2)

Task 10.2 selects nominal **64,000 bps AAC-LC**, 24 kHz mono, through a regular
Android `MediaCodec` encoder (`audio/mp4a-latm`) and `MediaMuxer`. This is a
requested target bitrate; vendor rate-control behavior is not assumed identical.
The selection is recorded in `b94f075`'s task-10.1 evidence and is provisional.

The benchmark's available API 35 Google x86_64 emulator had
`c2.android.aac.encoder` at all three tested rates. The 4-second fixture sizes
were 35,123 bytes at 64 kbps, 42,970 bytes at 80 kbps, and 50,855 bytes at
96 kbps. All rates reported 3.968 seconds, zero positive boundary gap, 245,336
microseconds total trim, and 30,667 microseconds maximum drift. Synthetic
decoded-window RMS ratios were 0.972–0.991, 0.973–0.980, and 0.972–0.989 at
64, 80, and 96 kbps respectively. Since higher rates showed no measured
duration or boundary advantage, 64 kbps is the smallest tested representation
without a measured disadvantage. These results do not establish a quality
winner: manual natural-speech A/B listening is still `manual_listening_pending`,
and no AAC run has qualified the Poco F3 ARM64 path.

Durable generation and Media3 playback use ordered `audio_segment` artifacts.
Each segment is one bounded narration chunk and remains independently ready,
retryable, invalidatable, and playable. Chapters group segments for navigation,
progress, and the later one-file-per-chapter export. Segment order is chapter
ordinal then segment sequence; existing chapter/paragraph/sentence/clause,
protected-span, and 507-symbol model-limit boundaries are unchanged. No
segment crosses a chapter boundary.

The app adds no silence to compensate for AAC priming, padding, or a codec
boundary. Explicit punctuation/chunk pauses are rendered in the PCM input and
versioned as audio processing. A future encoded boundary defect fails validation
instead of being repaired with guessed silence. If compatible platform AAC-LC is
unavailable or fails during configuration, encoding, muxing, or validation, an
existing verified ready artifact remains untouched. A segment without one may
be published as validated private PCM16 WAV for in-app playback; this is not an
M4A export fallback, and export reports AAC unavailability rather than changing
codec, bitrate, or file type.

Temporary raw PCM is deleted only after the selected AAC or WAV artifact passes
validation, is atomically published under `ready-audio`, and its Room segment
checkpoint is `READY` with checksum, size, duration, and provenance and no active
retry/reference. Failed or interrupted work never replaces a ready artifact.
Referenced temporary PCM remains available for retry or fallback; unreferenced
abandoned temporary files are handled by the existing 24-hour stale-temporary
reconciliation. Source documents, canonical text, Room state, and verified
ready audio are not implicit cleanup targets.

## Verified PCM-to-M4A publication (task 10.3)

`AndroidMediaCodecAacEncoder` in `tts-onnx` accepts only a private, validated
24 kHz mono PCM16 WAV, requests the regular platform AAC-LC encoder at nominal
64 kbps, and writes MediaMuxer output below temporary storage. `AndroidM4aValidator`
checks the M4A boxes, AAC-LC track, 24 kHz mono metadata, positive duration, and
MediaExtractor readability. `AudioArtifactPublisher` then uses the existing
atomic artifact store to checksum and publish the candidate before completing
the Room `READY` provenance checkpoint.

Replacement candidates use a unique ready-audio filename rather than replacing
the path held by the current Room row. Therefore encode, validation, publication,
or Room-checkpoint failure preserves the old verified file; staging PCM remains
available for retry. When no ready artifact exists, AAC failure can select a
validated private `.wav` fallback. Portable callers pass `portable = true`,
which reports the actionable AAC failure and never selects that fallback.

Focused and connected verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :tts-onnx:testDebugUnitTest \
  :tts-onnx:compileDebugAndroidTestKotlin \
  :tts-onnx:connectedDebugAndroidTest \
  :tts-onnx:lintDebug
```

The API 35 Google x86_64 emulator passed the real MediaCodec/M4A validation and
fallback instrumentation tests. The Poco F3 ARM64 vendor AAC path remains
unqualified; no generated audio artifact is committed.

## Export manifest schema (task 10.4)

`playback-export/src/main/resources/export-manifest-v1.schema.json` freezes the
machine-readable `citac-knjiga-export-manifest` schema at version `1`. Its
canonical serializer emits UTF-8 JSON with the declared field order; arrays are
ordered by chapter ordinal, file sequence, and attribution ID. Source and audio
checksums use lowercase hexadecimal SHA-256. Durations are positive integer
milliseconds; audio files declare 24 kHz mono metadata and a safe relative
destination path. Each file carries the Room generation key, model-package and
voice checksums, preprocessing/pronunciation versions, inference-settings hash,
and audio-processing version. Attribution entries are references with HTTPS
source URLs, not copied document text.

The JVM codec/validator tests use the committed
`playback-export/src/test/resources/export-manifest-v1.json` fixture. Validation
also checks chapter/file duration sums, unique IDs and paths, contiguous order,
required provenance, and rejects private URI paths or unknown JSON fields.
SAF destination selection, naming, metadata writing, progress/retry, and export
UI are implemented in the task-10.5 section below; durable progress/retry and
destination recovery remain task 10.6.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:lintDebug
```

## SAF audiobook export (task 10.5)

`playback-export` now exposes `ContentResolverDocumentTree` for an
`ACTION_OPEN_DOCUMENT_TREE` result and attempts to persist read/write access.
The exporter never converts a tree URI to a filesystem path. It enumerates
provider children, creates files with `DocumentsContract`, and writes through
the provider's output stream. Chapter audio names are deterministic
`0001-sanitized-title.m4a`/`.wav` values (chapter order is one-based and
zero-padded); each selected chapter produces exactly one physical audio file.
WAV segments are streamed into one validated WAV. M4A or mixed inputs are
decoded and re-encoded at chapter scope, never byte-concatenated. The manifest
retains ordered source segment IDs. Case-insensitive collisions use `-2`, `-3`,
and so on. Existing names are not replaced unless an overwrite plan is
explicitly confirmed. Available private covers are copied with detected image
MIME/extension, and `manifest.json` contains the canonical export-manifest-v1
metadata and provenance. No generated export is checked in.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:connectedDebugAndroidTest \
  :playback-export:lintDebug
```

## Persistent SAF export recovery (task 10.6)

Room schema version 2 adds `export_job_chapter`. The parent `export_job` stores
the project, destination URI, ordered selection, manifest/cover names, status,
and progress; each chapter row stores its ordered segment plan, destination
filename/URI, temporary URI, verified SHA-256/size/duration, state, attempts,
and last error. `RUNNING`/`WRITING` checkpoints are reconciled to queued/pending
on restart. Resume and retry verify persisted completed files first and only
write incomplete or failed chapters.

The SAF writer creates a uniquely named `.incomplete` document, flushes and
closes it, reads it back to verify size and SHA-256, then finalizes it with the
provider's document rename operation and verifies the finalized URI again.
Providers that cannot safely rename documents fail with an actionable
destination-finalization error and leave no falsely complete chapter name.
Destination permission loss records the failed job/chapter while retaining
private source and ready audio; selecting an available destination is required
for recovery. No destination filesystem path or atomic filesystem rename is
assumed.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest :core:connectedDebugAndroidTest \
  :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:connectedDebugAndroidTest \
  :playback-export:lintDebug
```

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

## Export storage preflight and failure isolation (task 10.7)

Export preflight runs after read-only validation of every selected private
READY source and before `plan()` creates any private chapter assembly or SAF
temporary document. The deterministic estimate is:

```text
WAV chapter = max(sum known source bytes,
                  ceil(duration_ms * 24,000 * 2 / 1,000) + 44) + 44
M4A chapter = max(sum known source bytes,
                  ceil(duration_ms * 64,000 / 8,000)) + 4,096
target = sum(chapter estimates) + cover bytes
       + 4,096 * (chapter count + 1)
       + 4,096 + 1,024 * chapters + 512 * segments + 256 * attributions
provider temporary = max(chapter estimate, cover bytes, manifest estimate)
private temporary = sum(chapter estimates) + max(M4A chapter PCM scratch, 0)
margin = max(ceil(10% * (target + provider temporary)), 65,536)
```

The metadata allowance covers provider/file metadata and book/chapter
metadata; the cover is counted at its verified private size; and the manifest
allowance covers its fixed and per-record fields. The provider must have
`target + provider temporary + margin` free bytes. Private storage must have
`private temporary + margin` free bytes because the current planner assembles
all chapter files before provider writing. A SAF provider that reports no
capacity is handled conservatively: preflight fails with instructions to use a
capacity-reporting provider or another destination. No SAF URI is converted to
or interpreted as a filesystem path.

`ExportFailureIsolationAndroidTest` injects a destination write failure after
the plan is persisted. The export job and chapter checkpoint become failed,
but the source WAV bytes and SHA-256, Room project and READY segment including
generation provenance, and the verified playback queue remain identical.
Export temporary documents are the only allowed destination-side residue.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :playback-export:testDebugUnitTest \
  :playback-export:compileDebugAndroidTestKotlin \
  :playback-export:connectedDebugAndroidTest \
  :playback-export:lintDebug
```

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

## EPUB adversarial coverage (task 11.1)

The security tests keep generated attacks bounded in memory and runtime. They
cover canonical Zip Slip variants, exact entry/total/individual/ratio limits,
the committed encrypted and malformed-XML fixtures, DTD/entity declarations,
external DTD/URI references, and cleanup-safe source import. Parser coverage
also keeps valid spine content usable when a well-formed NCX map is empty while
reporting a navigation warning. No network or external file is opened.

Focused verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :document-epub:testDebugUnitTest \
  --tests '*EpubAdversarialSecurityTest' \
  --tests '*EpubSecurityValidatorTest' \
  --tests '*EpubDocumentParserTest'
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

## Progressive playback demonstration (task 9.8)

`ProgressivePlaybackAndroidTest` is the deterministic offline proof for listening
to a completed chapter while a later chapter generates. It creates two Room
chapters, publishes a verified synthetic 24 kHz mono WAV for chapter 1, and
leaves chapter 2 pending. The real `BoundedGenerationRunner` claims chapter 2
and is held inside its injected generator while a real `ExoPlayer` plays chapter
1 through `Media3PlaybackQueuePlayer`. The test observes the active position
advance, releases generation, then verifies that Room publication grows the
queue from one item to two while chapter 1, its position, and playing state are
preserved. The generated bytes are a test double; no model or network is used.

Run it on an attached Android test device or emulator:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :playback-export:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.playback.export.ProgressivePlaybackAndroidTest
```

The proof passed on 2026-08-30 on `emulator-5554`, API 35 x86_64 (`1 test,
0 failed`). It does not claim production TTS-model inference or physical-device
audio qualification; those remain covered by their existing task-specific
proofs and device gates.

## External chapter playback qualification (task 10.8)

Task 10.8 is **blocked and remains unchecked**. The reproducible inventory check
is intentionally separate from the app's Media3 player and creates no export
media:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  scripts/check_external_audio_players.sh
```

The 2026-08-30 check found one connected target only:

- `emulator-5554`, Google API 35 `sdk_gphone64_x86_64`, x86_64.
- Third-party packages: `com.homoludens.citacknjiga.debug` and
  `com.homoludens.citacknjiga.debug.test` only; both are the application/test
  packages and are excluded from the external-player count.
- Installed system audio-related packages included
  `com.google.android.apps.youtube.music` and `com.android.musicfx`.
  YouTube Music is not a verified local-file player on this image, and
  `com.android.musicfx` is an audio effect service, not a player.
- Android package resolution returned no external handler for
  `audio/mp4`, `audio/aac`, `audio/m4a`, or `audio/x-m4a`.
- No physical Android device was connected, and no external player APK was
  available in the workspace to install.

Consequently, no chapter M4A was opened in an external player and no audible
playback, duration, metadata, or ordering claim is made. The existing
`SafAudiobookExporterAndroidTest` still verifies offline chapter assembly,
MediaExtractor readability, metadata, and persistent SAF behavior, but its fake
provider and test process do not satisfy this task's external-player gate.
Repeat the inventory after installing two genuine local-audio players, then
export through the corrected chapter-level SAF flow from commits `f61f59d`,
`1fba650`, `631f690`, and `c7fba1a`; record each package name and the manual
duration, audible-playback, metadata, and chapter-order results here before
checking off 10.8. Exported media must remain outside the repository.

## Library lifecycle, regeneration, and progress (tasks 1-3)

Project deletion is app-owned cleanup only. Confirmation stops playback, cancels
the project WorkManager work, marks the project `DELETING`, removes canonical
private source/text/cover/audio/temporary artifacts, and deletes the Room rows.
The original PDF/EPUB SAF URI and external export destinations are never cleanup
targets. Startup reconciliation retries interrupted deletion after restart.

Chapter and whole-book regeneration are destructive to the selected generated
audio scope: existing segments are invalidated before the selected request is
queued, while source text and unrelated chapters/projects remain intact. Failed,
canceled, or low-storage runs leave no invalid ready segment and expose the
persisted retry state.

Generation progress is approximate Unicode-aware narratable-word progress. The
Room aggregate counts estimated words only for `READY` segments, falls back to
segment counts for legacy rows, and survives process restart. Download progress
is separate and reports bytes/percentage followed by a visible verifying state.

Focused lifecycle and progress verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :core:testDebugUnitTest :app:testStandardDebugUnitTest \
  :playback-export:testDebugUnitTest
```

## Network permission and offline model-download policy (tasks 4.2-4.5)

The direct model-download path is the only planned application network boundary.
Both `standard` and `fdroid` declare `android.permission.INTERNET` for the
download transport and `android.permission.ACCESS_NETWORK_STATE` so WorkManager
can enforce its connected-network constraint. The latter does not transfer data.
The app sets `android:usesCleartextTraffic="false"` and does not request Wi-Fi
control permissions. These permissions do not authorize document upload,
telemetry, arbitrary URLs, or network-backed generation.

The immutable policy is recorded in
`model-tools/native/source-closure-v1.json` and matches the task-4.1 Kotlin
descriptors exactly:

| Engine | Release asset | Expected bytes | Outer SHA-256 |
|---|---|---:|---|
| Kokoro | `kokoro-serbian-dragana-v2.zip` | 338316574 | `58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b` |
| VITS | `serbian-vits-1.0.0.zip` | 121971081 | `45aa231e12c8a317f0d093cfb56d54066e19b53561b4ac401661109f19abe5dc` |

Only `https://github.com/homoludens/citac_knjiga/releases/download/` paths for
those two assets are permitted. `ModelDownloadConfig` rejects arbitrary URLs and
pins the release tag, filename, version, byte count, and outer SHA-256. The
`HttpsModelDownloadTransport` is the only allowlisted network source; it streams
through a connected-network WorkManager job into private temporary storage.
Document import, generation, and runtime dependency acquisition remain offline.

After transfer, the installer verifies the outer SHA-256 and delegates manifest,
declared-artifact, engine/API/ABI, and runtime checks to the selected package
store. Kokoro and VITS publish independently through atomic `active.zip` and
`last-valid.zip` slots. A short, oversized, disconnected, canceled, corrupt, or
incompatible download deletes its temporary file and leaves the previous active
package unchanged. The diagnostics screen exposes separate Kokoro and VITS
download actions with downloading, verifying, installed, failed, canceled, and
queued states; it never displays private paths or credentials. User-requested
downloads require a connected network but are not silently delayed by Android's
battery-not-low or storage-not-low scheduler flags. Explicit package-size and
stream bounds continue to report storage and oversized-download failures.

The variant-aware Gradle gate parses the actual AGP merged manifests rather than
checking source manifests:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:verifyModelDownloadManifests --no-daemon --max-workers=1
```

The gate requires `INTERNET` and the WorkManager connectivity permission, rejects
Wi-Fi control and other routine network permissions, and requires cleartext
traffic to be disabled for both `standardRelease` and `fdroidRelease`.
`python3 scripts/check_source_closure.py` additionally rejects network clients
and checks the exact descriptor/policy allowlist and offline operation boundary.

`LocalDiagnostics` is the central structured-log boundary. Event messages and
components must be stable category tokens. Attribute values are retained only
for safe categories, validated numbers/booleans, constrained IDs, and exact
SHA-256 hashes. Document text in Latin or Cyrillic, `content://` and `file://`
URIs, model/SAF paths, query and fragment data, and exception details are
replaced with redaction markers. This is independent of the debug-only verbose
flag, so release and F-Droid builds use the same redaction boundary.

Focused redaction verification:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest --tests '*LocalDiagnosticsTest' --no-daemon
```

Result: four diagnostics tests passed, including Cyrillic/Latin text,
`content://` and `file://` URI, path/query/fragment, exception, safe hash/ID,
safe category, and normal category-message cases.

Verification commands for the completed library/model-download change:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :core:testDebugUnitTest :document-epub:testDebugUnitTest \
  :document-pdf:testDebugUnitTest :tts-onnx:testDebugUnitTest \
  :playback-export:testDebugUnitTest :app:testStandardDebugUnitTest \
  :app:lintStandardDebug :app:lintFdroidDebug :core:lintDebug \
  :document-epub:lintDebug :document-pdf:lintDebug \
  :playback-export:lintDebug :tts-onnx:lintDebug \
  :app:verifyModelDownloadManifests \
  :app:assembleStandardRelease :app:assembleFdroidRelease

ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
python3 scripts/audit_dependencies.py
python3 scripts/check_source_closure.py
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
python3 scripts/check_fdroid.py --require-build
sh scripts/check_formatting.sh
python3 scripts/generate_release_docs.py
python3 scripts/validate_release_docs.py
```

For connected migration checks, put the SDK `platform-tools` first in `PATH`
so the `adb` client matches the Android SDK used by Gradle, then run
`AudiobookDatabaseMigrationTest` from `core`. The available emulator is API 35
`x86_64`; production ARM64 and the qualified production model remain separate
release gates. The repository contains no model payload or generated audio.

Task 11.2 release verification was completed with these additional commands,
all successful on 2026-08-30:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew test --no-daemon --max-workers=1

ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:compileStandardDebugAndroidTestKotlin :app:compileFdroidDebugAndroidTestKotlin \
    :core:compileDebugAndroidTestKotlin :document-epub:compileDebugAndroidTestKotlin \
    :tts-onnx:compileDebugAndroidTestKotlin :playback-export:compileDebugAndroidTestKotlin \
    --no-daemon --max-workers=1

ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:lintStandardDebug :app:lintFdroidDebug :core:lintDebug \
    :document-epub:lintDebug :tts-onnx:lintDebug :playback-export:lintDebug \
    --no-daemon --max-workers=1

ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:assembleStandardDebug :app:assembleFdroidDebug \
    :app:assembleStandardRelease :app:assembleFdroidRelease \
    --no-daemon --max-workers=1
```

## Cross-component recovery coverage (task 11.3)

Task 11.3 adds deterministic end-to-end coverage across preprocessing, Room,
generation, model import/runtime, playback, EPUB source import, and SAF export:

- `SerbianPreprocessingTest` compares Latin/Cyrillic golden vectors through
  phonemes, token IDs, chunk boundaries, and `GenerationKeyCalculator`.
- `CrossComponentGenerationRecoveryAndroidTest` uses file-backed Room,
  `StartupReconciliation`, `GenerationStateService`, and `BoundedGenerationRunner`
  to prove one-block selective regeneration and storage failure before/during work.
- `ModelPackageStoreTest` corrupts the only installed package and proves the
  downstream ONNX session is disabled without a production model.
- `CrossComponentPlaybackExportRecoveryAndroidTest` reconciles corrupt ready WAV
  audio, verifies the playback regeneration route, and resumes a Room-backed SAF
  export after destination loss without changing private project data.
- `EpubSourceRecoveryAndroidTest` parses the verified private EPUB copy after its
  source provider disappears.

The focused JVM suites, Android-test compilation, connected core/TTS/EPUB/export
suites on `emulator-5554` (API 35 x86_64), all module/flavor lint tasks, and
standard/F-Droid debug/release assemblies passed on 2026-08-30. The tests do not
claim sustained multi-device behavior or production-model execution.

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :core:connectedDebugAndroidTest :tts-onnx:connectedDebugAndroidTest \
    :document-epub:connectedDebugAndroidTest :playback-export:connectedDebugAndroidTest
```

## Sustained Android qualification matrix (task 11.4)

Task 11.4 remains **blocked and unchecked**. The reproducible environment
inventory is `scripts/run_android_qualification_matrix.sh`; it records installed
system images, AVDs, connected device API/model/ABI/fingerprint, and the
externally staged model-package checksum without creating model or audio
artifacts in the repository. The committed concise evidence report is
`reports/task-11-4-android-qualification.md`.

The 2026-08-30 inventory found only `emulator-5554`, Google API 35
(`sdk_gphone64_x86_64`/`emu64xa`), native x86_64. Installed system images were
API 29 Google Play x86 and API 35 Google APIs x86_64. The API 30 Android 11
image was not installed. The API 36 platform SDK was installed, but no API 36
system image, AVD, or connected device existed; API 36 system images were only
available as downloadable SDK packages. No physical Poco F3 was attached. The
known Poco evidence is Android 13/API 33 and cannot be relabeled as Android 16.

The production benchmark precondition was run against the API 35 emulator with
the verified external package and `WORKLOAD_SECONDS=15`; it exited 2 before
execution because `scripts/run_android_benchmark.sh` correctly requires native
Poco F3 `M2012K11AG`/`alioth`. The existing progressive playback control was run
with:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :playback-export:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.playback.export.ProgressivePlaybackAndroidTest \
  --no-daemon --max-workers=1
```

It passed one test in 48 seconds. It proves deterministic synthetic Room/Media3
queue continuity only; it is not production-model sustained generation. No
production progress, generated-audio continuity, force-stop, reboot, update,
or vendor battery-management result was obtained. The emulator snapshot had
battery 100%, battery temperature 25.0 C, thermal status 0, and low-power mode
0; these are not sustained qualification measurements. Do not mark 11.4 until
Android 11, a current production-capable target, Android 16, and a Poco F3
  vendor-policy run have actual evidence.

## Accessibility and localization verification (task 11.5)

Task 11.5 was verified on 2026-08-30. The focused Compose tests cover
generation progress semantics and actions, redacted failure text with retry,
player descriptions and touch actions, 2x font-scale layout reachability, and
English fallback resources. Module connected suites passed on the available
API 35 x86_64 emulator, and the standard/F-Droid debug/release assemblies and
all requested lint tasks passed.

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:connectedStandardDebugAndroidTest \
    :core:connectedDebugAndroidTest :playback-export:connectedDebugAndroidTest \
    --no-daemon --max-workers=1

ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:lintStandardDebug :app:lintFdroidDebug :core:lintDebug \
    :document-epub:lintDebug :tts-onnx:lintDebug :playback-export:lintDebug \
    --no-daemon --max-workers=1

ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:assembleStandardDebug :app:assembleStandardRelease \
    :app:assembleFdroidDebug :app:assembleFdroidRelease \
    --no-daemon --max-workers=1
```

The app connected suite has one unrelated existing failure in
`TypedTextProofAndroidTest` when no verified model package is staged; the new
accessibility tests pass. This does not qualify production model execution.

## Diagnostics/about view (task 11.6)

The start screen's `Дијагностика` action opens the in-app diagnostics/about
route. It reads the existing private model package through `ModelPackageStore`
and reports package identity/checksums and verification state, device/API/ABI
capability, the pinned ONNX Runtime CPU provider and `1/1` threads, app and Room
schema versions, internal storage used/available/capacity, attribution/license
references, offline/network policy, and available parity, benchmark, typed-text,
and export proof status. Model verification and filesystem inspection run off
the Compose UI thread. Missing or invalid data is rendered as an explicit
status with an actionable message; no raw validation exception is shown.

The `Извези редиговану дијагностику` action uses the Android document picker.
The export applies `DiagnosticRedactor` to every snapshot field and event, and
`LocalDiagnostics` retains at most 100 already-redacted events. It contains
only allowlisted categories, IDs, hashes, numbers, and statuses. It never
contains document text, sensitive URIs or paths, model contents, or raw
exceptions. The view reports that external-player playback evidence remains
pending for task 10.8; this task does not alter that gate.

Focused verification on the available API 35 x86_64 emulator:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :core:testDebugUnitTest :app:testStandardDebugUnitTest \
  :app:testFdroidDebugUnitTest :app:compileStandardDebugAndroidTestKotlin

ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homoludens.citacknjiga.DiagnosticsAboutUiTest
```

Both focused JVM/test-compilation and the two diagnostics Compose tests passed
on 2026-08-30. The app/module lint tasks and standard/F-Droid debug/release
assemblies also passed. No model package or generated diagnostics export is a
repository artifact.

## Dependency and license audit (task 11.7)

Run the audit offline with the local Android SDK and Gradle cache:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
python3 scripts/audit_dependencies.py
```

The command resolves standard and F-Droid release graphs plus module test
graphs, records 149 Android components, and writes the bundled assets:

- `app/src/main/assets/notices/dependency-license-inventory.json`
- `app/src/main/assets/notices/THIRD_PARTY_NOTICES.md`

License metadata comes from offline Gradle-cache POMs. Guava and Hamcrest
fallbacks are explicit because their cached POMs omit license fields; any
remaining empty or `UNKNOWN` license fails the audit. The report found no
incompatible or unmaintained production dependency to replace. Readium and
Sherpa-ONNX are excluded by the recorded production decisions. The generated
Gradle graph is temporary build output under
`build/reports/dependency-audit/` and is not a release asset.

## MVP capability audit (task 11.8)

Task 11.8 was audited on 2026-08-30 and remains **blocked and unchecked**.
The redacted requirement/scenario/task matrix is
`reports/task-11-8-mvp-capability-matrix.md`. It maps all 37 requirements and
43 scenarios in the six change specs, plus every task from 1.x through 11.x,
to reproducible evidence, results, limitations, and release impact.

The audit ran OpenSpec strict validation, 39 desktop model-tool tests, all 11
EPUB fixture validations, direct-parser tests, ONNX graph validation, package
manifest/hash validation, root JVM tests, all module lint tasks, offline
release-manifest checks, core/TTS/EPUB/export connected suites, focused app
accessibility/diagnostics/player/recovery tests, dependency audit, AAC fixture
benchmark, and standard/F-Droid debug/release assemblies. These available
checks passed except for the expected full-app typed-text proof failure when
the intentionally unstaged verified model package is absent. The fresh full
desktop parity rerun timed out; the committed v2 report remains the recorded
26/26 parity evidence.

The release-candidate decision is **no**. Task 10.8 still lacks two external
Android audio players. Task 11.4 still lacks Android 11/API 30, Android
16/API 36, and physical Poco F3 vendor battery-management qualification. The
natural Serbian AAC listening/Poco AAC check and public model legal gate also
remain open. No model, audio, or generated report is committed.

## CI validation (task 12.1)

`.github/workflows/ci.yml` runs on pull requests and pushes to `main` with
read-only repository permissions. It uses JDK 21, the checked-in Gradle 8.10.2
wrapper and checksum, Android platform/build-tools 35/35.0.0, CMake 3.22.1,
and NDK 26.1.10909125. Gradle and uv caches are keyed by the checked-in wrapper
and `model-tools/uv.lock`; no model package, secret, signing key, or generated
audio is fetched or uploaded.

The workflow runs the repository's whitespace check, all root JVM tests,
`check`, every debug lint task for the standard/F-Droid app and library modules,
and `assembleStandardDebug` plus `assembleFdroidDebug`. Its model job installs
the system eSpeak-NG binary, checks out `kokoro_sr` at the recorded commit into
temporary runner storage, runs the contract/golden preprocessing tests, and
validates the blocked declaration-only model manifest. It never needs model
weights or a model-package archive.

The local CI-equivalent commands are:

```sh
bash scripts/check_formatting.sh
KOKORO_SR_ROOT=/path/to/kokoro-serbian/src \
  model-tools/.venv/bin/pytest \
  model-tools/tests/test_preprocessing_contract.py \
  model-tools/tests/test_preprocessing_validation.py \
  model-tools/tests/test_model_package_manifest.py
model-tools/.venv/bin/python model-tools/scripts/validate_model_package_manifest.py
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SDK_ROOT=/path/to/Android/Sdk \
  ./gradlew test check \
  :app:lintStandardDebug :app:lintFdroidDebug :core:lintDebug \
  :document-epub:lintDebug :tts-onnx:lintDebug :playback-export:lintDebug \
  :app:assembleStandardDebug :app:assembleFdroidDebug \
  --no-daemon --max-workers=2
```

## Emulator instrumentation coverage (task 12.2)

`scripts/run_android_instrumentation.sh` is the reproducible named-device
runner for the integration boundary. It requires a connected API 35 device or
emulator, defaults to `emulator-5554`, sets `ANDROID_SERIAL`, compiles the
instrumentation APKs, and runs the complete core, document-EPUB, and
playback-export suites plus the model-free app recovery proof:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
DEVICE=emulator-5554 \
scripts/run_android_instrumentation.sh
```

The coverage includes Room migration/data preservation, valid and hostile SAF
EPUB fixtures with private-storage cleanup, deterministic generation recovery,
real ExoPlayer position/speed restoration across Room/player recreation, and
export destination failure isolation. Existing generation, playback, and export
instrumentation suites run unchanged alongside the new focused tests.

On 2026-08-30, the runner passed 20 core, 4 document-EPUB, 18 playback-export,
and 1 app instrumentation test on API 35 Google x86_64 `emulator-5554`.
Fixtures and generated files are private temporary test data and are removed
by the tests; no model package, audio, or report artifact is committed. The
app command intentionally excludes the existing typed-text proof because the
verified production model package is not available in the repository.

## Reproducible toolchain locks (task 12.3)

`gradle/toolchain.lock.json` is the checked-in contract for external build and
runtime tools. The Android dependency versions remain centralized in
`gradle/libs.versions.toml`; the lock contract checks that catalog, while Gradle
also enforces exact resolved versions through the five module `gradle.lockfile`
files and `settings-gradle.lockfile`. `gradle.properties` enables strict Gradle dependency verification. The
Gradle 8.10.2 wrapper verifies its distribution SHA-256, and
`gradle/verification-metadata.xml` verifies all resolved artifacts, including the
ONNX Runtime Android 1.29.0 AAR.

The desktop environment is exact Python 3.11.14 and uv 0.10.12. `uv.lock`
contains hashes for all registry artifacts and the Kokoro git revision. Direct
ONNX, ONNX Runtime, ONNX Script, Torch, SoundFile, and test dependencies are
exactly pinned in `model-tools/pyproject.toml`; `model-tools/.python-version`
prevents interpreter drift. eSpeak-NG 1.52.0 and its Android source commit,
CMake/NDK versions, data closure checksums, and observed native-library checksum
are recorded in `model-tools/native/espeak-data-manifest-v1.json`.

Verify all static locks and locally installed tools with the exact environment:

```sh
uv python install 3.11.14
(cd model-tools && uv sync --locked --python 3.11.14)
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  model-tools/.venv/bin/python scripts/verify_toolchain.py --scope all
```

The verifier checks wrapper/catalog/lockfile consistency, strict dependency
verification, Python/uv/eSpeak versions, JDK Temurin 21.0.7, installed Android
platform 35, build-tools 35.0.0, platform-tools 37.0.1, CMake 3.22.1, NDK
26.1.10909125, the eSpeak-NG git pin, and the ONNX Runtime AAR checksum. It
exits non-zero with the missing or mismatched requirement rather than selecting
latest. It does not inspect model bytes, generated audio, or build caches.

The model lock and declaration checks are:

```sh
(cd model-tools && uv lock --check && uv sync --locked --python 3.11.14)
model-tools/.venv/bin/python scripts/verify_toolchain.py --scope model
model-tools/.venv/bin/python model-tools/scripts/validate_model_package_manifest.py
```

The Android wrapper/toolchain, JVM tests, Android-test compilation, lint, and
standard/F-Droid debug/release assembly checks are:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  python3 scripts/verify_toolchain.py --scope android
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew test --no-daemon --max-workers=1 --console=plain --dependency-verification=strict
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew \
    :app:compileStandardDebugAndroidTestKotlin :app:compileFdroidDebugAndroidTestKotlin \
    :core:compileDebugAndroidTestKotlin :document-epub:compileDebugAndroidTestKotlin \
    :tts-onnx:compileDebugAndroidTestKotlin :playback-export:compileDebugAndroidTestKotlin \
    :app:lintStandardDebug :app:lintFdroidDebug :core:lintDebug \
    :document-epub:lintDebug :tts-onnx:lintDebug :playback-export:lintDebug \
    :app:assembleStandardDebug :app:assembleStandardRelease \
    :app:assembleFdroidDebug :app:assembleFdroidRelease \
    --no-daemon --max-workers=1 --console=plain --dependency-verification=strict
```

The Android CI job installs the same API/build-tools/CMake/NDK revisions and
runs the Android scope verifier before its Gradle checks. The SDK manager and
CI action channels are not treated as application dependencies; the installed
package revisions are checked after setup. Physical ARM64 qualification and
source-build/release-signing work remain separate tasks and are not claimed by
12.3.

## F-Droid variant and scanner checks (task 12.5)

The existing `fdroid` distribution flavor remains separate from `standard` and
keeps its application ID suffix (`.fdroid`), version suffix (`-fdroid`), and
empty flavor-specific dependency surface. The deterministic policy is
`fdroid/check-config-v1.json`; it records the four assembly tasks, locked
metadata, required `INTERNET` permission for pinned model assets, forbidden
routine permissions/dependencies/payloads, allowed generated native libraries,
and required notice assets.

The scanner-like check is:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  python3 scripts/check_fdroid.py --require-build
```

It invokes the static toolchain and native source-closure/network-policy checks,
inspects the actual F-Droid merged manifest and release APK with `aapt2`/ZIP
parsing, and checks permissions, HTTPS-only policy, tracker/proprietary dependency markers, embedded
model/audio/secrets, declared native libraries, version/build metadata, and
bundled notices. It does not create a report file, model package, audio file,
or release artifact.

The F-Droid policy allows `INTERNET` only for the two configured GitHub Release
model assets. It does not permit document import, generation, or runtime
dependency acquisition over the network.

On 2026-08-30, the closest available reproducible local run used the locked
Android SDK/JDK, strict Gradle dependency verification, offline dependency
resolution, `--rerun-tasks`, and one Gradle worker. JVM tests, `check`, all
module/app lint tasks, model-download merged-manifest verification, and standard plus
F-Droid debug/release assemblies passed. The source closure verified seven
eSpeak data files and 196 source files; the F-Droid APK check passed. The
native eSpeak library is source-built; the only binary dependency exception is
the explicitly locked Maven ONNX Runtime AAR and its generated APK libraries,
as recorded by task 12.4.

No `fdroid`, `fdroidserver`, Androguard, apktool, or JADX executable is
installed in this environment. The checker therefore uses the repository's
source/manifest/lock checks plus SDK `aapt2` and APK inspection. This is not a
real F-Droid scanner run, and no claim is made that external F-Droid metadata
or scanner policy has been evaluated. The full locked toolchain check passes
when run with `model-tools/.venv/bin/python` (Python 3.11.14); the system
Python 3.13 interpreter is intentionally not accepted by that lock.

## Release documentation bundle (task 12.6)

The redacted documentation bundle is generated at
`reports/release-docs/`. Generation first refreshes the existing dependency
license inventory from the locked Android runtime/test graph, then derives the
CycloneDX SBOM and writes the attribution, privacy, threat, benchmark, and
model-package compatibility documents. It never copies model/audio payloads or
the full dependency notice list into the report bundle; notice files remain
authoritative at `app/src/main/assets/notices/` and are referenced by SHA-256.

Run from the repository root:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
  python3 scripts/generate_release_docs.py
python3 scripts/validate_release_docs.py
```

Use `python3 scripts/generate_release_docs.py --skip-audit` only when checking
rendering against an already generated notice inventory. The validator checks
the source/toolchain/package/legal/parity/benchmark input hashes, exact SBOM
coordinates, notice hashes, output set, and redaction rules. It does not sign,
publish, or approve an application or model package; those are tasks 12.7 and
12.8.
