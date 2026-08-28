# citac_knjiga — Offline Serbian EPUB-to-Audiobook Android App

Turns DRM-free ebooks into persistent, locally generated audiobooks using the
custom Dragana Serbian Kokoro voice. Offline-first, open source, F-Droid
target.

## Status

- OpenSpec change `build-serbian-audiobook-mvp` is in progress:
  `openspec/changes/build-serbian-audiobook-mvp/` (proposal, design, 6 specs,
  12 task phases).
- Phase 2 model export/parity is complete: tasks 2.4-2.7 froze and exercised
  the FP32 parity contract, desktop ONNX validation, and the bounded Sherpa
  experiment; task 2.8 selects direct ONNX Runtime Android `1.29.0` as the
  implementation target. Android parity and device qualification remain later
  tasks.
- Task 3.1 defines the strict v1 model-package manifest, blocked legal fixture,
  SHA-256 identity, and declaration validator under `model-tools/package/`.
  `model-tools/scripts/prepare_public_manifest.py` is the separate public
  generation path: it hashes the actual local payload and never mutates the
  blocked negative-test fixture. The confirmed derived-package treatment is
  CC BY-SA 4.0 with required attribution; no weights or package archive are
  checked in.
- Task 3.3 expands the self-authored Serbian golden corpus to 22 pinned desktop
  reference vectors with explicit category coverage and deterministic
  regeneration metadata.
- Task 3.5 extends the corpus to 26 vectors with exact 506/507/508-symbol
  operational-limit cases and a 523-symbol punctuation-free paragraph split at
  `[0,506]` and `[506,523]`; seeded WAV metadata and chunk-aware desktop parity
  are checked against the pinned runtime. Packaging/import and Android
  qualification remain later tasks.
- Task 3.6 confirms from the pinned `kokoro_sr` source that exact Serbian
  phonemization is eSpeak-NG-backed and therefore needs a native engine/data
  component on Android, not pure Kotlin pronunciation rules. The project
  accepts the GPL-3.0-or-later dependency; source/data provenance and release
  notices are recorded under `model-tools/native/`.
- Task 3.7 checks in the platform-neutral preprocessing resources and contract:
  exact vocabulary lookup, IPA normalization, chunking limits, resource
  checksums, pinned `kokoro_sr`/eSpeak-NG provenance, and fail-closed Android
  compatibility status. Native eSpeak-NG source/build provenance and the
  checked-in data closure are now recorded under `model-tools/native/` and
  packaged as Android assets; see `model-tools/preprocessing/`.
- Task 3.8 adds the desktop golden preprocessing gate: all 26 vectors are run
  through the contract stages without model inference, the first divergent
  vector/stage is reported, and package creation fails closed before archive
  publication on any mismatch.
- The 2026-08-27 v1 limit-boundary deviations are retained in
  `model-tools/parity/fp32-parity-v1-decision.md`. Active `fp32-parity-v2`
  revises only maximum absolute error from `0.1` to `0.13`; a fresh desktop
  run passes all 26 vectors and the rationale is recorded in
  `model-tools/parity/fp32-parity-v2-decision.md`.
 - Task 4.5 is complete for the available native x86_64 Android test execution:
   debug variants package both `x86_64` and `arm64-v8a`, and the pinned eSpeak-NG
   source builds for ARM64, its verified data closure is installed privately,
   and JNI phonemization reproduces the desktop CLI behavior. A host-equivalent
   native probe matches all 26 preprocessing vectors. The API 35 x86_64
   emulator now runs the native x86_64 debug bridge and passes the five-vector
   smoke gate and the full 26-vector instrumentation gate. Both debug and
   release include the pinned ONNX Runtime dependency; release assembly remains
   explicitly ARM64-only, but ARM64 Android execution is still unqualified and
   remains a production/device blocker.
 - Task 4.6 adds the direct ONNX Runtime Android session boundary. `OnnxTtsSession`
   validates the manifest names, dtypes, fixed/dynamic shapes, int64 duration
   relationship, and 24 kHz mono float PCM conversion. It selects the Dragana
   row using `min(token_count - 2, 509)`, configures sequential CPU inference
   with one intra-op and one inter-op thread, and closes input tensors, result
   values, session options, session, and environment deterministically.
   `ModelPackageStore.readArtifact` reads verified model/style payloads from the
   private archive; no model is copied into the APK. The connected boundary gate
   uses a small deterministic ONNX fixture. The ignored local production graph
   exists, but a complete model package is not checked in; Android
  production-graph parity is still task 4.9.
- Task 4.7 adds `OnnxAudioOutputValidator` at the tensor-to-PCM boundary. It
  applies the frozen 24 kHz mono, finite, strict `(-1,1)`, RMS/silence, exact
  sample-count, and speed-scaled `pred_dur` duration contracts, with typed
  failure codes.
- Task 4.1 creates the source-buildable Android foundation with `app`, `core`,
  `tts-onnx`, `document-epub`, and `playback-export` modules. The project pins
  Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.1.10, JDK 21 toolchains,
  compile/target SDK 35, build-tools 35.0.0, and Android 11 (`minSdk 30`) with
   an explicit production `arm64-v8a` filter (debug packages both `x86_64` and
   `arm64-v8a` for the available emulator and target device). `tts-onnx` is the
   only module that currently carries the selected ONNX Runtime Android 1.29.0
   dependency in both debug and release; later Android behavior remains
   outside this session boundary unimplemented.
- Task 4.2 adds a single Compose `start` route, Material 3 foundation screen,
  and a manual `AppContainer` created by `CitacKnjigaApplication`. Dependencies
  remain constructor-provided and test-replaceable; feature modules do not
  depend on the app container. `core` owns the small `LocalDiagnostics` API,
  which emits structured local events to Logcat and redacts unknown, document,
  text, and URI attributes by default.
- Android application variants are explicit: `standard` and `fdroid`
  distribution flavors combine with the existing `debug` and `release` build
  types. The F-Droid-oriented flavor has its own application ID suffix and no
  proprietary service or network permission. The app manifest removes the
  network permissions contributed by the ONNX Runtime dependency because
  inference is local; no model or document behavior is implied by this
  boundary.
- Task 4.3 adds `ModelPackageStore` in `tts-onnx`: SAF streams are first copied
  to private temporary storage, then ZIP entries, the v1 manifest identity,
  declared artifact sizes/SHA-256 values, and the pinned Android/Serbian
  compatibility contract are checked before publication. The active archive is
  kept at `filesDir/model-packages/active.zip` and the prior verified archive at
  `last-valid.zip`; an invalid active archive is rolled back on next access.
- Task 4.4 adds resource-backed Kotlin cleanup, IPA normalization, vocabulary,
  boundary-token, and chunking stages in `tts-onnx`. The 26-vector corpus is
  wired directly as JVM resources and Android test assets. The default
  pronunciation stage fails closed until native eSpeak-NG is available; tests
  inject committed reference IPA only to qualify the later Kotlin stages, not to
  claim Android phonemization parity.
- Task 4.8 adds the single-route typed Serbian proof screen. It accepts Latin and
  Cyrillic input, exposes preprocessing/model diagnostics and explicit generation
  states, writes validated app-private 24 kHz mono PCM16 WAV, and plays it with
  the local Android PCM API. Generation remains fail-closed when no verified
  model package is installed; EPUB, Room, Media3, and export are not included.
- Task 4.9 adds the Kotlin `DeviceParityEvaluator`, active `fp32-parity-v2`
  metric declarations, and `DeviceParityReportStore` for atomic app-private JSON
  reports containing device, build, runtime, model, threshold, vector metrics,
  and status identity without document text. The new desktop vector exporter
  writes a text-free audio manifest plus token/speed sidecar for all 26 IDs;
  `DesktopOnnxParityVectorLoader` verifies that external bundle and supports
  chunked model calls through the existing runner. JVM tests and the connected API 35
  x86_64 instrumentation fixture pass. A separately named production test is
  opt-in, requires native `arm64-v8a`, reads the external bundle and verified
  package from private test paths, and persists a report with real device/build/
  runtime/model identities. The production test passes all 26 vectors on the
  Poco F3 native ARM64 process. The runner persists a non-passing `blocked`
  report when no verified package is installed.
- Task 4.10 is complete on the Poco F3 (`M2012K11AG`, API 33, native
  `arm64-v8a`). With Wi-Fi and mobile data explicitly disabled, the typed-text
  screen accepted `Dobar dan.`, showed successful Serbian preprocessing and
  verified model provenance, generated a 24 kHz mono PCM16 WAV, and played it
  through the local `AudioTrack` path. The captured proof WAV was 61,244 bytes,
  1.275 seconds, and SHA-256
  `7c07ef70d63d0c7cad414c4a7f5cdd079ed1475c7e9d9574fd9eb9867391ee93`.
  Device staging was removed after verification; the model package and WAV
  remain uncommitted.
- Production model loading streams the verified model ZIP entry to a private
  temporary file before ONNX Runtime path-based session creation, avoiding the
  Java-heap copy. Torch voice archives are read through their central directory
  because Android streaming ZIP does not reliably enumerate PyTorch stored
  entries with data descriptors. Temporary files are removed on every path.
  The measured waveform contract is 600 samples per predicted duration frame.
- Task 5.1 now has an opt-in Android benchmark runner and SDK-`adb` wrapper.
  It drives the existing native Serbian typed-input preprocessing and direct
  ONNX Runtime CPU session until at least 900 seconds of validated 24 kHz PCM
  is generated, while discarding PCM instead of creating a generated artifact.
  The app-private JSON report contains only device/build/runtime/model
  identities, numeric timing/resource measurements, statuses, and explicit
  Android metric limitations. The wrapper verifies the locally qualified v2
  package archive and disables Wi-Fi/mobile data for the run. The full Poco
  run completed on 2026-08-28: 902.45 audio seconds in 1,594.649 wall seconds
  (RTF 1.767), model load 2,964 ms, peak PSS 908,320,768 bytes, CPU
  114.108% average/206.336% peak, battery 52% to 50%, battery temperature
  35.7 to 37.0 C, and thermal status 0 with no throttling observed. RTF misses
  the unchanged provisional <= 1.0 gate; task 5.5 owns the explicit decision.

## Repository layout

| Path | Purpose |
|---|---|
| `citac_knjiga.md` | Original project brief (source of truth for intent) |
| `openspec/` | Spec-driven change artifacts (proposal / specs / design / tasks) |
| `kokoro_sr_dragana_voice/` | Known-good Dragana checkpoint bundle (epoch-005), LFS-tracked |
| `python_voice_test/` | Earlier self-contained Dragana inference bundle (epoch_2nd_00002) |
| `speak_2.py` | Ad-hoc CPU inference test script (points at training-repo paths) |
| `model-tools/` | Desktop model tooling, native eSpeak-NG provenance/data manifest, reference captures, export wrapper, package schema/validator, and preprocessing contract/resources (Phase 1–3) |
| `app/`, `core/`, `tts-onnx/`, `document-epub/`, `playback-export/` | Minimal Android foundation modules (task 4.1) |

## Key technical facts

- Model: Kokoro-82M fine-tuned for Serbian (Južne vesti base) then on the
  Dragana single-speaker dataset. 24 kHz mono output.
- Runtime: pinned Kokoro fork `semidark/kokoro@b96fef95` (NOT PyPI 0.9.4 —
  weight-norm difference makes PyPI produce noise).
- Phonemizer: `kokoro_sr.phonemes.phonemize_serbian` — eSpeak-NG `--ipa=3 -v sr`
  plus symbol normalization/audit against the Kokoro v1 vocabulary.
- Input limit: 507 operational phoneme symbols per model call, 510 hard.
- Voice tensor shape: `[510, 1, 256]`; sampled at `min(len(ipa), 509)`.
- Export wrapper (task 2.1): `model-tools/export/wrapper.py` exposes the
  deterministic tensor boundary (token IDs + selected style row + speed →
  24 kHz float32 PCM + pred_dur) that task 2.2 exports to ONNX. See
  `model-tools/export/README.md` for the interface contract.
- Desktop parity (task 2.5): run
  `model-tools/.venv/bin/python model-tools/scripts/run_parity.py` to compare
  the PyTorch CustomSTFT baseline with ONNX Runtime CPU over all committed
  vectors. Reports are written to
  `model-tools/parity/fp32-parity-report.json` and
  `model-tools/parity/fp32-parity-report.txt`.
- Android runtime decision (task 2.8): direct
  `com.microsoft.onnxruntime:onnxruntime-android:1.29.0` is selected; CPU is
  the acceptance baseline and XNNPACK is a separately measured variant. See
  `model-tools/android-runtime-decision.md`; this does not claim Android or
  device qualification.
- Preprocessing contract (task 3.7):
  `model-tools/preprocessing/preprocessing-contract-v1.json` has identity
  `4b4991dda9e26d7edf9d35f41bce395fcd9215fa771c4bc453a190560a897213` and
  binds three checked-in JSON resources to the 26-vector exact-stage contract.

## Conventions

- Commit style: `type(scope): description` (see `AGENTS.md`).
- Commit after every task; a fresh-context agent per task.
- Deployment/environment steps live in `DEPLOYMENT.md`.
- The Android wrapper and dependency checksums live under `gradle/`; local SDK
  discovery uses `ANDROID_HOME` or `ANDROID_SDK_ROOT` rather than committing a
  machine-specific `local.properties`.
