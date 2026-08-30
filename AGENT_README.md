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
- Task 8.1 adds `SerbianNarrationChunker` for structured narration block text.
  It prefers sentence and clause boundaries, protects Serbian abbreviations,
  numbers, URLs, email, citations, digraphs, and grapheme clusters, preserves
  punctuation, reports Unicode code-point source ranges, and emits configurable
  pause metadata. It measures candidates through the existing Serbian
  preprocessor and defaults to the verified 507-symbol operational cap (510
  hard limit, 512 sequence length).
- Task 8.2 adds `GenerationStateValidator` and the transactional
  `GenerationStateService` in `core`. Project, chapter, segment, and generation
  run transitions are explicit and compare-and-set persisted through Room DAO
  methods. Run/segment attempts increment when work starts, actionable failures
  remain in the existing `last_error` fields through retry, and parent ownership
  plus completion prerequisites are checked without changing schema version 1.
- Task 8.3 adds `BoundedGenerationRunner` in `core`. It starts a queued run,
  claims one pending segment at a time in a Room transaction, delegates bounded
  inference/output validation through an injected suspend function, publishes
  through `AtomicArtifactStore`, records provenance only after publication, and
  checks persisted pause/cancel state between segments. Coroutine cancellation
  releases an incomplete claim to `PENDING`; schedulers, notifications, playback,
  export, and recovery hosts remain outside this task.
- Task 8.4 adds the WorkManager queue adapter in `core`. `GenerationWorkScheduler`
  reconciles Room state before enqueueing queued runs, uses one `KEEP` unique
  work name per generation run, and applies offline, battery, and storage
  constraints with exponential retry backoff. `RoomGenerationQueue` reuses
  `StartupReconciliation` after app start, reboot, or update; the worker receives
  its bounded runner through `GenerationWorkerFactory`, while foreground policy
  is supplied separately by task 8.5. Android instrumentation coverage verifies
  unique-work coordination and scheduling; execution on a connected device is
  still pending because no device is currently attached.
- Task 8.5 adds `GenerationNotificationController` and the optional foreground
  notification path for `GenerationWorker`. Room-derived notifications show the
  book title, ready/total segment progress, and failed-segment count, with
  explicit pause, resume, and cancel actions. Actions update the persisted run
  state before coordinating the unique WorkManager request; notification and
  foreground-service permissions are declared. Android 16 host qualification
   is explicitly deferred because the available Poco F3 runs Android 13; the
   queue remains runner-independent for later qualification.
- Task 8.7 adds the core `GenerationFailurePolicy` and bounded three-attempt
  segment retry path. ONNX validation, inference, provenance, and atomic-write
  failures retain stable actionable codes in the existing `last_error` field;
  invalid output is rejected before ready publication. Startup reconciliation
  accepts expected generation keys and marks only stale, corrupt, or
  mismatched-provenance ready segments stale, leaving verified unaffected audio
  reusable. Recovery qualification and the multi-chapter proof remain tasks
  8.9-8.10.
- Task 8.8 adds `GenerationStoragePolicy` and `GenerationStorageCleanup` in
  `core`. Preflight estimates the largest temporary artifact plus requested
  ready audio and applies a default 10%/64 KiB safety margin against private
  filesystem capacity. The bounded runner can recheck that estimate around each
  segment; a capacity drop records `INSUFFICIENT_STORAGE` without replacing
  completed audio or changing source/project metadata. ENOSPC-style atomic
  writes are categorized as non-retryable `STORAGE` failures. Cleanup is an
  explicit choice limited to stale temporary or reviewed orphan ready-audio
  files.
- Task 8.9 adds recovery coverage for the durable generation boundary. JVM tests
  simulate abrupt termination during inference, temporary writing, and atomic
  publication, then verify that reconciliation keeps verified segments ready,
  returns claimed work to `PENDING`, cleans stale temporary artifacts, and
  leaves private source data intact. Android instrumentation repeats the
  recovery checks with a file-backed Room database reopened before
  reconciliation, and covers simulated reboot/update lifecycle markers,
  injected low-capacity storage, and an unavailable source provider. The
  connected suite passed on the available API 35 x86_64 emulator.
  Force-stop/kill, physical reboot, package-update installation, real ENOSPC,
  and SAF export-destination loss were not executed. Export is intentionally
  unimplemented until task 10; those device-only cases are not claimed as
  proven.
- Task 8.10 adds `MultiChapterResumeAndroidTest`, an EPUB-backed two-chapter
  instrumentation demonstration with four deterministic segment fixtures. It
  stops after the first segment is verified, injects the durable
  `RUNNING`/`GENERATING` crash snapshot, reopens and reconciles file-backed Room,
  then resumes only pending segments while asserting the completed segment's
  path, bytes, and SHA-256 are unchanged. The generator is a deterministic test
  double rather than production ONNX audio; no Poco is attached, so OS-level
  force-stop and real model inference are not claimed.
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
  35.7 to 37.0 C, and thermal status 0 with no throttling observed. Device
  performance is informational: RTF and peak memory have no acceptance limits
  and do not block application implementation. Task 5.2 uses a short runtime
  matrix to compare controlled CPU and XNNPACK thread configurations.
  The 2026-08-28 Poco F3 matrix completed CPU and XNNPACK runs at provider
  threads 1/2/4; results and report-integrity checks are recorded in
  `DEPLOYMENT.md`.
- Task 10.1 now has a separate opt-in Android `MediaCodec`/`MediaMuxer`
  AAC-LC/M4A benchmark that needs no model package. It uses one deterministic
  24 kHz mono PCM16 synthetic Serbian-consonant fixture, tests 64/80/96 kbps,
  measures availability, size, encode/track duration, independent-segment
  boundary drift, and decoded WAV-relative RMS/zero-crossing metrics. The
  available API 35 x86_64 emulator completed all three rates on
  `c2.android.aac.encoder`; the measured run and its synthetic/natural-speech
  limitations are recorded in `DEPLOYMENT.md`.
- Task 10.2 selects nominal 64 kbps AAC-LC for MVP encoded audio. Durable and
  playback artifacts remain ordered Room audio segments, while chapters group
  them for navigation, progress, and later one-file export. A validated private
  PCM16 WAV is the in-app fallback when platform AAC-LC is unavailable or fails;
  it is not an M4A export fallback. No codec-workaround silence is inserted, and
  temporary raw PCM is deleted only after validated atomic publication and the
  Room `READY` checkpoint. The decision is provisional: the task-10.1 fixture is
  synthetic, manual natural-speech listening is pending, and API 35 x86_64
  emulator results do not qualify the Poco F3 ARM64 AAC path.
- Task 10.3 adds reusable `AndroidMediaCodecAacEncoder` and
  `AudioArtifactPublisher` components. They validate private 24 kHz mono PCM16
  WAV input, encode nominal 64 kbps AAC-LC through MediaCodec/MediaMuxer, check
  M4A structure plus Android MediaExtractor metadata, then publish and record
  Room provenance. Replacement candidates use unique ready paths, preserving a
  previous ready artifact and staging PCM through encode, validation,
  publication, or Room failures. A segment without an existing artifact can
  use a validated private WAV fallback. Real AAC output and fallback pass on
  the API 35 x86_64 emulator; ARM64 vendor qualification remains open.
- Task 10.4 defines `citac-knjiga-export-manifest` schema v1 in
  `playback-export`. Canonical UTF-8 JSON uses explicit field order, lowercase
  hexadecimal SHA-256 values, millisecond durations, 24 kHz mono file metadata,
  relative export paths, per-file Room generation provenance, and HTTPS
  attribution references. The validator enforces ordered contiguous chapters
  and files, duration sums, ready-audio provenance, and rejects URI paths,
  document text fields, unknown fields, and inconsistent hashes/durations. It
  does not select destinations, generate names, write metadata, or run export.
- Task 6.1 defines the initial Room-owned project schema in `core`: books,
  chapters, narration blocks, audio segments, generation runs, model packages,
  playback positions, and export jobs. Enum values persist by stable name;
  ownership and artifact relations use explicit foreign-key actions and ordered
  unique indexes.
- Task 6.2 publishes Room schema version 1 under `core/schemas/`, configures
  `MigrationTestHelper` with the AndroidX test runner, wraps relation reads in
  Room transactions, and leaves destructive migration fallback disabled. The
  connected migration tests verify the exported schema and reject an unknown
  newer database version.
- Task 6.3 centralizes app-private paths in `core/storage/AppPrivateStorage`.
  The root remains Android `filesDir`, with stable `sources`,
  `model-packages`, `canonical-text`, `covers`, `temporary`, `ready-audio`, and
  `diagnostics` areas plus existing proof/benchmark/parity areas. Path methods
  validate components and canonical containment without creating or publishing
  files; `filesDir/model-packages/active.zip` and `last-valid.zip` remain
  compatible with the model store.
- Task 6.4 adds `core/storage/AtomicArtifactStore`. It buffers and syncs private
  temporary writes, validates and hashes them before publication, requests an
  atomic move with a documented regular-move fallback, and provides stale
  temporary/orphan cleanup without inspecting Room state.
- Task 6.5 adds `core/generation/GenerationKeyCalculator`. Dependency keys are
  SHA-256 digests of canonical model/voice identities, preprocessing and
  pronunciation versions, sorted inference settings, and audio-processing
  version. Generation keys add the ordered token IDs to that dependency
  identity; neither key includes timestamps or database state.
- Task 6.6 adds `core/reconciliation/StartupReconciliation`. On startup it
  removes only age-qualified files below `temporary/`, returns running runs and
  generating segments to queued/pending states, and marks missing, checksum-
  invalid, format-invalid, or stale-provenance ready segments as `STALE`.
  Chapter/project readiness is downgraded accordingly; source, model, and
  ready-audio files are never deleted by reconciliation.
- Task 6.7 adds core coverage for persisted Room project/generation transitions
  and relations, one-block generation-key invalidation, failed publication
  preserving the existing ready artifact, and idempotent reconciliation through
  the real Room adapter. The focused JVM suite and four connected emulator tests
  pass.
- Task 7.2 adds an isolated `readium-spike/` consumer. Readium 3.1.0 compiles
  and opens the EPUB2/3 fixtures through `shared`/`streamer`, preserving exact
  metadata, cover, spine order, and NCX/nav order. The 3.3.0 Maven artifacts
  resolve but require compile SDK 36; the 3.3.0 source checkout builds with
  Gradle 9.1/AGP 9/Kotlin 2.3.20. Dependency size, lazy malformed-fixture
  behavior, source-build details, and F-Droid implications are documented in
  `readium-spike/README.md`. Readium is not added to the production importer;
  task 7.3 owns comparison and selection.
- Task 7.3 compares that Readium control with the disposable stdlib-only
  `epub-direct-spike/` over all 11 task-7.1 fixtures. The direct platform
  ZIP/XML approach is selected for task 7.4 because it matches the measured
  metadata, cover, spine, navigation, basic EPUB3 content, and malformed
  recovery needs without adding a production dependency. The experiment only
  observes security markers; enforcement remains task 7.5. Details are in
  `openspec/changes/build-serbian-audiobook-mvp/epub-importer-decision.md`.
- Task 7.4 adds `SafEpubSourceRepository` in `document-epub`. A selected
  `ContentResolver` URI is copied through `AtomicArtifactStore` to private
  temporary storage, fingerprinted with SHA-256, checked against the Room-backed
  project index, and atomically published as `sources/<projectId>/source.epub`.
  The persisted URI is provenance only; later document work uses the private
  `sourcePath`. Duplicate, copy, publication, and index failures leave existing
  projects untouched and clean temporary output. Archive/XML security is task
  7.5; structured parsing and cleanup continue through tasks 7.6-7.7.
- Task 7.5 adds a read-only `EpubSecurityValidator` before source fingerprinting
  and publication. Strict defaults reject 40 or more entries, 128 KiB or more
  total expansion, 8 KiB or more per entry, 100:1 or more compression ratio,
  XML payloads at 64 KiB, and nesting deeper than 64 elements. It rejects
  traversal, encrypted/DRM-marked ZIPs, duplicate/malformed archives, DTD/entities,
  external resource references, and malformed XML. DTD/entity rejection,
  bounded XML inspection, and the external resolver keep the parser safe on
  Android implementations that do not expose the optional JAXP hardening
  feature switches. Validation never extracts entries; failures expose typed codes and clean the
  private temporary source. These are inspection limits, not EPUB metadata or
  content mapping, which remains task 7.6+.
- Task 7.6 adds `EpubDocumentParser`, a stdlib `ZipFile`/DOM mapper that accepts
  only the exact published private source path and repeats security validation
  before opening it. It emits metadata, cover bytes, recursive nav/NCX TOC,
  spine-ordered chapters, typed narration blocks, deterministic source locators,
  and explicit `SKIPPED` blocks for unavailable or unsupported content. It can
  project chapters and blocks into the existing Room entities; Markdown,
  diagnostics, preview, and generation remain later tasks.
- Task 7.7 adds `EpubMarkdownRenderer` and `EpubCanonicalTextService`. Each
  chapter is rendered as deterministic UTF-8 Markdown with source-locator
  comments, typed block formatting, and retained recovered text for skipped
  content. The service atomically publishes chapter files below
  `canonical-text/<projectId>/` and an actionable warning report below
  `diagnostics/<projectId>/import-warnings.json`; publication rollback prevents
  partial Markdown. Metadata and navigation parser diagnostics are surfaced
  without changing the Room schema; preview UI remains task 7.8.
- Task 7.8 adds `EpubImportPreviewService`. SAF EPUBs are copied to validated
  app-private temporary staging, parsed, and rendered in memory before acceptance.
  The Compose start route shows metadata, spine-ordered chapter titles and
  narration text, canonical warnings, and a source/text/cover/diagnostics
  storage estimate. Acceptance is the first operation that publishes the
  source, index record, canonical Markdown, and warning report; cancel and
  parse/security failures discard staging. There is no editing UI, generation,
  or playback behavior in this slice. Focused coverage is in
  `EpubImportPreviewTest`.
- Task 7.9 adds the bounded `EpubChapterProofService`. After preview acceptance
  it maps one selected chapter into Room, generates its narratable text through
  the existing Serbian preprocessing/ONNX proof engine, atomically publishes
  one verified app-private 24 kHz mono PCM16 WAV, records model/voice and
  generation provenance, and plays the result through local `AudioTrack`.
  `EpubChapterProofAndroidTest` passed on the Poco F3 (`M2012K11AG`, API 33,
  native `arm64-v8a`) with Wi-Fi and mobile data disabled. This is a one-shot
  vertical proof only; durable whole-book generation remains task 8.
- Task 9.1 adds the minimal Media3 playback service. It observes Room-ready
  audio, accepts only verified private 24 kHz mono artifacts, builds a
  snapshot playlist, and keeps player/session resource ownership separate from
  generation state. The service manifest is merged through `app`'s existing
  `playback-export` dependency. JVM lifecycle/repository tests and the API 35
  x86_64 connected Room-source test pass; queue updates, controls, position
  persistence, and invalid-audio UX remain later playback tasks.
- Task 9.2 adds a Room-backed library controller and Compose library/book views.
  Accepted EPUBs now project title, author, cover, ordered chapters, and
  narration blocks into Room. Views map chapter/segment readiness, generation
  progress and failures, available listening position, and private storage use;
  player controls and position persistence remain later playback tasks.
- Task 9.3 adds a non-owning MediaController-backed playback controller and
  Compose controls for play/pause, clamped seek/jumps, completed-chapter
  navigation and selection, configurable jump values, and supported playback
  speeds. The service remains the sole ExoPlayer owner and its Room-ready
  playlist is a snapshot; position persistence, system media integration,
  dynamic queue updates, and missing-audio UX remain later tasks.
- Task 9.4 adds `PlaybackPositionPersistence` over the existing Room
  `playback_position` row. The Media3 service restores a saved ready
  chapter/segment, clamps its position to the current item, falls back to the
  first available segment when the saved target is missing, and accepts only
  supported finite speeds. It polls once per second with a two-second write
  throttle, also reacts to player events, and flushes on service teardown.
  Room remains persistence-only and Media3 remains the playback owner.
- Task 9.5 configures the Media3 service's single default notification provider
  with a stable audiobook channel, chapter/book/author metadata, app return
  intent, and standard skip controls. Speech audio attributes keep Media3
  responsible for requesting and abandoning focus; transient and ducking
  losses pause and resume only when previously playing, permanent loss does not
  resume, and noisy-output handling pauses through ExoPlayer. MediaSession is
  the standard lock-screen/headset/Bluetooth command surface; no queue or
  generation ownership was added.
- Task 9.6 adds the service-owned `PlaybackQueueCoordinator`. It observes the
  verified Room-ready stream, orders and deduplicates segment IDs by chapter,
  sequence, and ID, and updates the existing Media3 player without a second
  player. Queue changes preserve the active media ID and position and retain
  playback; an update that would remove the currently playing item waits for a
  player boundary or stopped playback. The controller and position persistence
   now consume the refreshed catalog. The progressive-playback demonstration
   remains task 9.8.
- Task 9.7 adds `PlaybackAvailabilityPolicy`. Room audio rows are checked before
  queueing for ready status, private-file existence, size/SHA-256, 24 kHz mono
  format, structural readability, generation key, and current model/run
  provenance. Android production adds `MediaExtractor` readability. Invalid
  rows remain unchanged in Room, are reported with an actionable
  `generation/retry/<segment>` route, and never enter Media3. If the currently
  playing item becomes invalid, the queue pauses it and skips to the next valid
  item; if none exists, playback remains stopped. Generation ownership stays
  outside playback.
- Task 9.8 adds `ProgressivePlaybackAndroidTest`, a deterministic offline
  integration proof using an in-memory Room database, the bounded generation
  runner, atomic private publication, `ReadyAudioRepository`, the existing
  `PlaybackQueueCoordinator`, and a real `ExoPlayer` through its service port.
  While a completed first chapter plays, later generation is held in inference;
  the active position advances, then the published second chapter grows the
  queue without replacing the active item or resetting its position. No model,
  network, or committed audio artifact is required.
- Task 10.8 is currently blocked, not complete. The 2026-08-30 inventory found only
  `emulator-5554` (API 35 x86_64), no external `audio/mp4`/AAC local-file handlers, and
  no physical device or installable player APK. `com.google.android.apps.youtube.music`
  is present but is not a verified local-file handler on this image;
  `com.android.musicfx` is an audio effect service. The reproducible check is
  `scripts/check_external_audio_players.sh`; no export media was created or committed.
- Task 11.1 adds bounded adversarial EPUB coverage. The JVM tests exercise the
  committed Zip Slip, compression, size, count, DTD/entity, external-resource,
  encrypted, and malformed-XML fixtures, plus generated canonical-containment,
  exact archive-limit, high-ratio, external-DTD, and external-URI cases. A
  well-formed empty NCX map is also tested to ensure malformed navigation warns
  without discarding valid spine content; no large attack artifact is added.
- Task 11.2 verifies the actual standard and F-Droid release merged manifests
  contain no `INTERNET` or `ACCESS_NETWORK_STATE` permission. `LocalDiagnostics`
  now keeps only safe category tokens, validated numeric values, SHA-256 hashes,
  and constrained IDs; free-form messages, document text, URI/path/query/
  fragment values, and exception details are redacted. Focused and release
  verification commands and the dependency/component/runtime audit are recorded
  in `DEPLOYMENT.md`.
- Task 11.3 adds cross-component recovery coverage: golden Serbian
  Latin/Cyrillic vectors retain identical phoneme/token/generation identities;
  Room reconciliation plus the bounded runner regenerates only a changed
  block; internal storage is checked before and during generation; a corrupt
  model disables ONNX opening; corrupt ready audio is reconciled and routed to
  regeneration; export storage is rejected before provider/Room mutation; and
  private EPUB source plus Room-backed SAF export survive source/destination
  provider loss and retry. Tests use deterministic fakes and no production
  model package.
- Task 11.4 remains blocked and unchecked. The 2026-08-30 inventory found only
  a Google API 35 x86_64 emulator, no API 30/API 36 bootable image or physical
  Poco F3. The progressive Media3 playback control passed one synthetic test,
  while the production benchmark correctly rejected the emulator because its
  native Poco F3 ARM64 guard was not met. Exact matrix evidence and the
  rerunnable inventory are in `reports/task-11-4-android-qualification.md` and
  `scripts/run_android_qualification_matrix.sh`; no sustained production
  generation qualification is claimed.
- Task 11.7 adds `scripts/audit_dependencies.py` and the root
  `writeResolvedDependencyInventory` Gradle task. The offline audit covers
  standard/F-Droid release graphs plus module test graphs, resolves 149 Android
  components, records local POM hashes and license metadata, and bundles the
  JSON inventory and Markdown notices under `app/src/main/assets/notices/`.
  No incompatible or unmaintained production dependency was found; Readium and
  Sherpa-ONNX remain intentionally excluded. Missing Guava/Hamcrest POM license
  fields use explicit upstream fallbacks, and unknown licenses fail the audit.

- Task 11.8 performed the MVP capability audit on 2026-08-30. The redacted
  matrix is `reports/task-11-8-mvp-capability-matrix.md`; it maps all 37 spec
  requirements, 43 scenarios, and every task from 1.x through 11.x to evidence
  and limitations. OpenSpec strict validation, desktop/fixture/JVM checks,
  available connected suites, manifest/privacy checks, dependency audit, and
  standard/F-Droid assemblies passed within their stated boundaries. The
  release-candidate decision is **no**: task 10.8 lacks two external players,
  task 11.4 lacks API 30/API 36 and physical Poco F3 qualification, the
  intentionally unstaged production model blocks the current app TTS proof,
  and natural Serbian AAC/Poco AAC listening remains pending. Task 11.8 stays
  unchecked.

- Task 12.1 adds `.github/workflows/ci.yml` for pull requests and `main` pushes.
  It checks repository whitespace, locked model-tool golden preprocessing and
  declaration-only model validation, all JVM tests, Gradle checks, standard and
  F-Droid debug lint, and both debug APK assemblies. The workflow declares JDK
  21, Android 35/build-tools 35.0.0, CMake 3.22.1, and NDK 26.1.10909125,
  caches only Gradle/uv inputs, and uses no model bytes, secrets, services, or
  emulator orchestration. The golden validator accepts `KOKORO_SR_ROOT` so CI
  does not depend on the original developer workstation path.

- Task 12.2 adds emulator coverage for v1-to-v2 Room data migration, private
  SAF EPUB fixture import and hostile-fixture rejection, deterministic generation
  recovery, real ExoPlayer position/speed restoration across Room/player
  recreation, and export destination failure isolation. The named-device runner
  is `scripts/run_android_instrumentation.sh`; it requires API 35 (default
  `emulator-5554`) and runs without the unavailable production model package.

- Task 12.3 adds `gradle/toolchain.lock.json` as the checked-in toolchain
  contract. Gradle 8.10.2 uses its distribution SHA-256, strict dependency
  verification metadata, and per-module dependency lockfiles. The version
  catalog is the Android dependency source of truth; the lock contract checks
  its AGP/Kotlin/Compose/AndroidX/ONNX/native tool versions. Python 3.11.14,
  uv 0.10.12, exact model-tool dependencies, the pinned Kokoro/eSpeak-NG
  commits, Android API 35/build-tools 35.0.0, CMake 3.22.1, NDK 26.1.10909125,
   JDK Temurin 21.0.7, and the ONNX Runtime Android AAR SHA-256 are verified by
   `scripts/verify_toolchain.py`. Generated caches, model payloads, and audio
   remain excluded; unavailable required tools fail verification explicitly.

- Task 12.4 records the complete native/runtime source closure in
  `model-tools/native/source-closure-v1.json`. The locked eSpeak-NG source
  regenerates the seven checked-in Android data files byte-for-byte and builds
  the ARM64 JNI library from CMake/NDK source; the only prebuilt runtime input
  is the explicitly locked Maven ONNX Runtime AAR. `scripts/check_source_closure.py`
  rejects unexpected native/prebuilt/model files in Android source roots while
  allowing generated `build`/`.cxx` output and the documented Gradle Wrapper
  JAR. Model packages remain user-imported and separately verified.

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
- Task 10.5 adds the one-shot SAF export boundary in `playback-export`.
  `ContentResolverDocumentTree` uses `DocumentsContract` child queries and
  provider streams, while `SafAudiobookExporter` accepts only Room `READY`
  audio below private `ready-audio/` with matching size/SHA-256. Files use
  one-based zero-padded chapter order plus sanitized titles; each selected
  chapter produces exactly one physical audio file while the manifest retains
  ordered source segment IDs. WAV segments are streamed into one validated WAV;
  M4A or mixed inputs are decoded and re-encoded rather than byte-concatenated.
  Collisions get deterministic numeric suffixes unless the caller explicitly
  confirms replacement. The export writes verified audio, an inferred cover
   image when available, and canonical manifest-v1 JSON. Persistent progress,
   retry, and destination-loss recovery are now task 10.6; target/temporary
   capacity estimation is covered by task 10.7 below.
- Task 10.6 adds Room schema v2 `export_job_chapter` checkpoints and a
  resumable export coordinator. Each ordered chapter persists its source
  segment IDs, target name/URI, provider temporary URI, verified hash/size/
  duration, state, attempts, and error. SAF output is written to a uniquely
  named `.incomplete` document, read back and hashed, then finalized through
  provider document rename. Providers without safe rename fail clearly rather
  than exposing a partial final artifact. Restart reconciliation returns
  interrupted writes to pending; retry rechecks verified files and rewrites
  only missing/failed chapters while retaining private project/audio.
- Task 10.7 adds deterministic export preflight. For each chapter, WAV is
  estimated as the larger of known source bytes and 24 kHz mono PCM bytes,
  plus a 44-byte header; M4A is the larger of known source bytes and nominal
  64 kbps AAC bytes, plus 4 KiB container overhead. Target usage then adds
  cover bytes, 4 KiB metadata per book/chapter file, and a manifest allowance
  of 4 KiB plus 1 KiB/chapter, 512 bytes/segment, and 256 bytes/attribution.
  Peak SAF temporary usage is the largest planned output; private scratch is
  the sum of chapter outputs plus the largest decoded PCM WAV scratch file
  needed while assembling an M4A chapter. The required margin is the larger of
  10% of target plus provider scratch and 64 KiB. A provider with unknown capacity is
  rejected before any temporary document or private assembly is created.
  Android proof coverage injects destination write failure and verifies source
  bytes/checksum, Room project/READY/provenance, and the playback queue remain
  unchanged; only export job/checkpoint state changes.
- Task 11.5 adds Compose accessibility coverage and user-facing recovery states.
  Library and player controls expose meaningful descriptions, progress ranges,
  state descriptions, live-region updates, and generation pause/resume/cancel/
  retry actions. Serbian strings remain the default resources and English
  fallbacks cover the important app, generation, import, export, and player
  states. Large-font, redacted-error, and English-resource checks pass; export
  and import expose cancellation, retry, and destination recovery without
  displaying stored paths or raw failure details.
- Task 11.6 adds a reachable Serbian-first diagnostics/about route. It loads
  active model verification, device/API/ABI/runtime capability, app/schema
  versions, storage capacity, attribution/license references, offline policy,
  and proof status without blocking Compose. Its user-selected text export
  uses the central diagnostic redactor and bounded event history; it excludes
  document text, URIs/paths, model contents, and raw exceptions. Missing model,
  device, storage, and attribution data have explicit recovery guidance.

## Conventions

- Commit style: `type(scope): description` (see `AGENTS.md`).
- Commit after every task; a fresh-context agent per task.
- Deployment/environment steps live in `DEPLOYMENT.md`.
- The Android wrapper and dependency checksums live under `gradle/`; local SDK
  discovery uses `ANDROID_HOME` or `ANDROID_SDK_ROOT` rather than committing a
  machine-specific `local.properties`.
