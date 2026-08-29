## Context

See `proposal.md` for motivation and scope. The repository currently contains a known-good Serbian Kokoro checkpoint bundle and a small Python inference script, but not the pinned Kokoro runtime or `kokoro_sr` preprocessing source referenced by that script. The bundle declares 24 kHz audio, a custom Dragana voice/style tensor, and a roughly 313 MB PyTorch model. Android export, tensor signatures, voice indexing, tokenizer parity, and sustained device performance remain unproven.

The application must remain offline-first, handle hours of generated audio and Android process death, avoid proprietary services, and support F-Droid builds. One experienced Python/data-science developer will initially maintain the project, so module and framework complexity must remain proportional to proven needs.

## Goals / Non-Goals

**Goals:**

- Retire model export and Serbian preprocessing risks through runnable desktop and Android parity gates before document work.
- Keep every pipeline boundary persistent, inspectable, hash-addressed, and recoverable.
- Support playing completed work while generation continues.
- Make model packages independently versioned and distributable from the application.
- Maintain a source-buildable, privacy-preserving Android release path suitable for F-Droid.

**Non-Goals:**

- General-purpose ebook reading, arbitrary document conversion, cloud services, model training, or voice cloning on Android.
- Perfect preservation or narration of every EPUB construct.
- Text PDF, OCR, M4B, multiple voices, pronunciation-editor UI, or in-app Markdown editing in this change.

## Decisions

### 1. Use risk-gated vertical slices

Implementation proceeds through: pinned desktop reference, FP32 ONNX parity, typed-text Android inference, one known EPUB chapter, durable whole-book generation, playback, and chapter-file export. Each slice ends in a runnable demonstration. Correctness failures block dependent work; performance measurements inform later optimization but do not block implementation.

Building the full Android shell first was rejected because a failed model export or incompatible phonemizer would invalidate much of that investment.

### 2. Start with direct ONNX Runtime Android

The initial runtime boundary accepts token IDs, a selected Dragana style vector, speed, and any additional verified tensors, and returns float PCM. Desktop tools export the graph and compare it to the pinned PyTorch CPU reference before Android consumes it. Task 2.8 selects and pins `com.microsoft.onnxruntime:onnxruntime-android:1.29.0`, with CPU as the acceptance baseline and XNNPACK as an explicitly measured provider variant; NNAPI and reduced/custom runtime builds remain optimization experiments after parity. The dependency, checksum, ABI, thread, and remaining Android validation contract is recorded in `model-tools/android-runtime-decision.md`.

Sherpa-ONNX was evaluated as the bounded alternative. It is rejected for the MVP because its high-level Kokoro frontend does not accept the project's precomputed Serbian token IDs, skips an unsupported phoneme, and produces different sample counts for all seven vectors. Making it viable would require a maintained custom frontend or source patch and a new parity gate. Quantization is deferred until FP32 or quality-preserving FP16 establishes the baseline.

### 3. Treat preprocessing as a versioned portable product component

The pinned desktop `kokoro_sr` pipeline is the reference. Its stages are made explicit: narration cleanup, Serbian normalization, phonemization, vocabulary lookup, boundary tokens, and model-safe chunking. Golden fixtures capture every intermediate representation so a Kotlin port, native phonemizer, or packaged resource implementation can be compared stage by stage.

The first implementation preference is pure Kotlin tables/rules where practical. If the reference depends materially on eSpeak-NG, package a pinned minimal native build and matching data through a narrow interface rather than approximating pronunciation. Python is not embedded in the Android app.

### 4. Use a structured book IR plus inspectable Markdown

Canonical import output consists of publication metadata, ordered chapters, typed narration blocks, stable source locators, and per-chapter Markdown. Markdown is an audit/debug/export artifact, not the only internal model. UI editing is omitted, but the data model retains enough provenance for later editing and selective regeneration.

The task-7.3 fixture comparison selects a direct platform ZIP/XML implementation, excluding Readium from production dependencies. The disposable experiment preserved metadata, cover, declared spine, navigation, basic EPUB3 content, and recoverable malformed content with no added artifact; its security markers are observation only. Task 7.4 should use `java.util.zip.ZipFile` and platform XML parsing, while task 7.5 separately implements and tests security enforcement. AnyDoc/Rust is deferred pending a later Android size, JNI, maintenance, and PDF-quality spike.

### 5. Use a small modular Android structure

Use Kotlin, Compose, coroutines/Flow, Room, DataStore, Media3, Storage Access Framework, and an ONNX runtime. Begin with modules `app`, `core`, `document-epub`, `tts-onnx`, and `playback-export`; keep screens as packages inside `app`. Use constructor injection through an application container initially instead of Hilt. Add a DI framework only when wiring or test replacement becomes a demonstrated burden.

Desktop Python utilities live separately under `model-tools` and produce immutable, checksummed model packages and test vectors consumed by Android tests.

### 6. Make Room authoritative for state and files authoritative for bulk artifacts

Room tables cover `book_project`, `chapter`, `narration_block`, `audio_segment`, `generation_run`, `model_package`, `playback_position`, and `export_job`. They store ordering, text needed for processing, status, provenance, hashes, paths, sizes, durations, retry/error data, and timestamps. Schema migrations are tested from the first published version.

App-private files are separated into source documents, canonical Markdown/IR snapshots, installed model packages, temporary generation files, verified segment/chapter audio, covers, and diagnostics. Exports exist only at user-selected SAF destinations. Database rows never mark a bulk artifact ready until an atomically published file passes validation.

### 7. Use content-addressed selective invalidation

An audio generation key covers normalized model input or token hash, model and voice checksums, preprocessing and pronunciation versions, speed/pause settings, and post-processing version. A matching ready row is reusable only if its file checksum and format remain valid. Changes invalidate the smallest affected segments; chapter/export artifacts depending on them become stale.

### 8. Coordinate generation through persistent short checkpoints

Room stores the durable queue and state machine. A coroutine worker executes one bounded segment or batch at a time, publishes progress, and checks pause/cancel flags between atomic units. WorkManager schedules and reconciles unfinished work and constraints. Foreground execution supplies the visible notification while active work requires it.

Because long-running WorkManager jobs and foreground-service rules continue to evolve, an Android 16 qualification spike was planned to compare long-running `CoroutineWorker` behavior with a user-started direct foreground service backed by the same Room queue. Android 16 is not available in the current environment, so this qualification is explicitly deferred until after the MVP. The queue semantics do not depend on either runner, allowing the execution host to change without migrating projects.

Media3 owns playback only; it never owns generation state. Playback observes ready audio from Room and updates its playlist at safe boundaries.

### 9. Separate development PCM from MVP stored/export audio

Typed-text and parity slices write PCM16 WAV for numerical inspection. Whole-book storage cannot retain uncompressed PCM by default because 24 kHz mono PCM16 consumes about 173 MB per audio hour. Before whole-book rollout, benchmark Android `MediaCodec` AAC-LC/M4A around 64–96 kbps mono for intelligibility, consonant preservation, encoder availability, segment boundaries, and Media3 behavior.

The MVP internally plays verified segment or chapter items and exports zero-padded chapter M4A files, cover, metadata, and a manifest. A single M4B container is deferred. Raw temporary PCM is deleted only after the encoded artifact is verified; user source and project metadata are never removed implicitly.

### 10. Copy imports and sandbox untrusted publications

SAF selects sources and export destinations. Successful import first copies the source to an app-private temporary path, verifies it, fingerprints it, then publishes it to the project. EPUB handling enforces canonical-path containment, entry count, total expansion, compression ratio, individual size, XML parser, nesting, timeout, and external-resource limits. Filenames are generated from internal IDs; source names are metadata only.

The F-Droid flavor has no routine network requirement or proprietary dependencies. Model download is not part of this change; users import a checksummed model package. Public model distribution remains gated on Južne vesti and Dragana rights and attribution evidence.

### 11. Separate correctness gates from performance reports

Golden preprocessing outputs must match exactly. FP32 PyTorch, desktop ONNX, and Android ONNX numerical thresholds are declared and versioned alongside the test vectors before a candidate is evaluated; failures are investigated rather than accepted by silently loosening thresholds.

A short Poco F3 comparison reports real-time factor and peak process memory for controlled CPU and XNNPACK configurations. These values have no acceptance threshold and do not gate downstream implementation. They provide a baseline for choosing a development configuration and deciding whether optimization is useful later.

## Risks / Trade-offs

- [The reference phonemizer source is absent from this repository] → Restore and pin it before export; no Android port begins from observed audio alone.
- [Kokoro export may contain unsupported or numerically unstable operations] → Export and validate subgraphs, pin opset/runtime, and keep Sherpa-ONNX as a bounded alternative.
- [The model may be too slow, hot, or memory-heavy] → Record short device measurements, continue the vertical slice, and test FP16, graph optimization, other thread counts, or a reduced runtime later if actual use warrants it.
- [A 313 MB model complicates APK and F-Droid distribution] → Keep the model outside the APK with checksummed manual import until distribution rights and packaging are settled.
- [Readium may be larger than an extraction-only importer needs] → Depend only on required modules and compare against a bounded direct-parser fixture spike.
- [Foreground execution rules vary by Android version and vendor] → Keep Room as the runner-independent queue, checkpoint frequently, and test Android 11, current Android, Android 16, Poco battery restrictions, reboot, and force-stop behavior.
- [Segment encoding can introduce gaps or damage Serbian consonants] → Keep WAV parity fixtures, perform raw/encoded AB comparisons, and test playlist transitions before selecting AAC parameters.
- [Export providers do not all support atomic rename or capacity reporting] → Use provider capability checks, per-file verification, persisted progress, conservative estimates, and collision-safe names.
- [Model/data rights may forbid public weight distribution] → Publish application code separately and block official weight release until documented legal review is complete.

## Migration Plan

1. Establish the repository and desktop reference tooling without publishing an Android production release.
2. Publish immutable internal model-package candidates only after checksum and parity validation.
3. Introduce Room schema version 1 with migration and destructive-migration protections before user projects exist.
4. Release Android proof and fixture builds to testers, collecting benchmark and recovery evidence without promising project compatibility.
5. Freeze model-package schema, database schema, file layout, and export manifest for the first public beta.
6. Publish app and model artifacts separately; if a release fails, roll back the application while retaining recognized prior model packages and projects.

## Open Questions

- Which exact license and redistribution terms apply to the Južne vesti-derived weights and Dragana attribution?
- After the reference repository is restored, does Serbian phonemization require eSpeak-NG at runtime or can the exact behavior be represented by portable rules and resources?
- Which AAC bitrate and segment/chapter grouping best balance consonant quality, gap behavior, regeneration cost, and storage on the tested devices?
