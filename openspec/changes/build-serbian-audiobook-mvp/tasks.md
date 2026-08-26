# Implementation Tasks

Effort ranges are planning bands for one developer, not delivery commitments. A short task is at most one focused session; phase ranges include integration and debugging uncertainty. Phases 1–5 are hard prerequisites for document and whole-book work.

## 1. Reference Runtime and Legal Inventory (rough phase effort: 3–7 days)

- [x] 1.1 Locate or restore the exact Kokoro 0.9.4 runtime and `kokoro_sr` source used by `speak_2.py`, then record immutable repository commits and local patches. → `model-tools/runtime-pins.md`
- [x] 1.2 Create a reproducible desktop environment lock for the known-good CPU inference path and verify the checked-in checkpoint and voice checksums. → `model-tools/uv.lock` + `model-tools/pyproject.toml`; smoke test `model-tools/scripts/smoke_inference.py` passes (checksums OK, RTF 0.52)
- [x] 1.3 Document model inputs, outputs, tensor shapes and dtypes, boundary tokens, voice/style lookup semantics, speed behavior, sample rate, randomness controls, and verified maximum input rule. → `model-tools/model-io.md` (max input verified 510 hard / 507 reference; seed control verified)
- [x] 1.4 Capture representative reference inputs plus intermediate IPA/tokens and PCM outputs from the pinned PyTorch CPU implementation. → `model-tools/reference/vectors.json` + 7 seeded PCM WAVs (seed 20260826), via `scripts/capture_reference_vectors.py`
- [x] 1.5 Investigate and document Dragana dataset license, attribution text, source URL, and trained-weight redistribution implications. → `model-tools/legal-inventory.md` §1.5
- [x] 1.6 Investigate and document Južne vesti corpus permissions and whether derived public weights may be redistributed. → `model-tools/legal-inventory.md` §1.6 (DUA terms pending — handle lookup blocked)
- [x] 1.7 Create a dependency/license inventory template covering app code, native libraries, model tooling, model files, datasets, fonts, and test fixtures. → `model-tools/dependency-inventory.md`
- [x] 1.8 Record a legal release gate that keeps model weights separate from public app releases until tasks 1.5 and 1.6 have defensible outcomes. → `model-tools/legal-inventory.md` §1.8

## 2. Model Export and Desktop Parity (rough phase effort: 1–3 weeks)

- [x] 2.1 Implement a deterministic export wrapper that exposes verified tensor inputs instead of hiding preprocessing inside the ONNX graph. → `model-tools/export/wrapper.py` + `model-tools/export/README.md`; `scripts/verify_export_wrapper.py` passes 7/7 reference vectors bit-identical (seed 20260826, single-threaded)
- [ ] 2.2 Export an FP32 ONNX candidate with pinned opset, named dynamic axes where required, and a machine-readable interface manifest.
- [ ] 2.3 Validate the exported graph with ONNX tooling and enumerate its operators, initializers, external data, input limits, and runtime memory footprint.
- [ ] 2.4 Define versioned FP32 parity metrics and thresholds for sample count, waveform error, spectral similarity, silence, clipping, and invalid values before evaluating candidates.
- [ ] 2.5 Build a desktop parity runner that compares PyTorch CPU and ONNX Runtime over every reference vector and emits machine-readable and human-readable reports.
- [ ] 2.6 Diagnose and resolve every threshold failure without weakening the recorded thresholds silently.
- [ ] 2.7 Time-box a Sherpa-ONNX compatibility experiment using the Serbian graph, tokens, Dragana voice data, and phonemization inputs, then record accept/reject rationale.
- [ ] 2.8 Select and pin the Android inference runtime only after direct ONNX Runtime and the bounded Sherpa-ONNX experiment have comparable evidence.

## 3. Model Package and Serbian Golden Corpus (rough phase effort: 1–2 weeks)

- [ ] 3.1 Define model-package schema version 1 with model, voice/style, vocabulary, configuration, preprocessing compatibility, test vectors, licenses, attribution, manifest, and SHA-256 checksums.
- [ ] 3.2 Build a deterministic packager that rejects undeclared files and verifies its completed package before publication.
- [ ] 3.3 Expand the golden corpus for Latin/Cyrillic equivalence, `č/ć/š/ž/đ`, `lj/nj/dž`, mixed scripts, foreign names, abbreviations, numbers, dates, currencies, measurements, Roman numerals, punctuation, URLs, email, citations, and page artifacts.
- [ ] 3.4 Store expected cleanup text, normalized text, phonemes, token IDs, protected spans, chunk boundaries, and reference audio metadata for each vector.
- [ ] 3.5 Add parity cases immediately below, at, and above the verified model input limit and for paragraphs with no convenient sentence boundary.
- [ ] 3.6 Decide from the pinned source whether exact Android phonemization can be pure Kotlin or requires a native eSpeak-NG component, and record the dependency and licensing decision.
- [ ] 3.7 Implement the selected portable preprocessing resources and a platform-neutral version/provenance contract.
- [ ] 3.8 Add desktop tests that report the first divergent preprocessing stage and block package creation on any golden mismatch.

## 4. Android Foundation and Typed-Text Slice (rough phase effort: 2–4 weeks)

- [ ] 4.1 Create the Android 11+ ARM64 project with `app`, `core`, `tts-onnx`, `document-epub`, and `playback-export` modules and pinned Gradle/JDK/SDK versions.
- [ ] 4.2 Add Compose navigation, a manual application dependency container, structured local diagnostics, and separate debug/release/F-Droid configuration without proprietary services.
- [ ] 4.3 Implement app-private model-package import through SAF with temporary copy, manifest validation, checksum verification, compatibility checks, and rollback to the last valid package.
- [ ] 4.4 Implement Kotlin preprocessing stages and load golden vectors as JVM and Android test fixtures.
- [ ] 4.5 Make Android normalized text, phonemes, token IDs, and chunk boundaries match every golden vector exactly.
- [ ] 4.6 Implement the pinned Android ONNX session and verified tensor-to-PCM boundary with bounded threads and explicit resource cleanup.
- [ ] 4.7 Add invalid-output checks for non-finite samples, silence, clipping, sample rate, sample count, and plausible duration.
- [ ] 4.8 Build the typed Serbian text proof screen showing intermediate diagnostics, generate/cancel state, model provenance, and playable WAV output.
- [ ] 4.9 Compare Android PCM with desktop ONNX vectors using the declared parity metrics and persist a device parity report.
- [ ] 4.10 Demonstrate the complete offline path `Kotlin text → Serbian preprocessing → ONNX → playable 24 kHz audio` on the Poco F3.

## 5. Device Qualification Gate (rough phase effort: 3–7 days)

- [ ] 5.1 Create a repeatable 15-minute representative benchmark that captures model load time, real-time factor, peak memory, CPU, temperature, throttling, and battery change.
- [ ] 5.2 Benchmark CPU and XNNPACK with controlled thread counts on the Poco F3 and record output parity for each configuration.
- [ ] 5.3 Test NNAPI only if CPU/XNNPACK miss the provisional gate, and reject configurations that partition poorly or alter output beyond thresholds.
- [ ] 5.4 Evaluate FP16, graph optimization, or a reduced runtime only if required by measured results; keep quantization out of this gate.
- [ ] 5.5 Record an explicit proceed, optimize, or stop decision against real-time factor ≤ 1.0, peak memory ≤ 1 GB, stability, and thermal behavior.
- [ ] 5.6 Stop downstream EPUB implementation and update the design if the qualified configuration cannot meet an acceptable gate.

## 6. Persistent Project Core (rough phase effort: 1–2 weeks; depends on gate 5)

- [ ] 6.1 Define Room entities, relations, indexes, enums, and constraints for books, chapters, narration blocks, audio segments, generation runs, model packages, playback positions, and export jobs.
- [ ] 6.2 Implement schema version 1, migration-test infrastructure, transaction boundaries, and protection against accidental destructive migration.
- [ ] 6.3 Define the app-private directory layout and file APIs for sources, model packages, canonical text, covers, temporary files, ready audio, and diagnostics.
- [ ] 6.4 Implement atomic temporary-write, fsync/close, validation, rename/publication, checksum, and orphan-cleanup helpers.
- [ ] 6.5 Implement generation-key and dependency-key calculation from tokens, model/voice hashes, preprocessing versions, inference settings, and audio-processing version.
- [ ] 6.6 Implement startup reconciliation for interrupted database states, temporary artifacts, missing ready files, checksum failures, and stale provenance.
- [ ] 6.7 Add unit and integration tests for Room transitions, selective invalidation, atomic publication, and reconciliation idempotence.

## 7. EPUB One-Chapter Vertical Slice (rough phase effort: 2–4 weeks)

- [ ] 7.1 Assemble redistributable EPUB 2/3 fixtures covering metadata, cover, NCX/nav, mismatched filename/spine order, nested headings, lists, notes, poetry, malformed content, and known attacks.
- [ ] 7.2 Spike minimal Readium `shared`/`streamer` integration and measure dependency size, API fit, source-buildability, metadata/spine fidelity, and F-Droid implications.
- [ ] 7.3 Compare Readium with a bounded direct ZIP/XML parser experiment and record the selected importer with objective fixture results.
- [ ] 7.4 Implement SAF EPUB selection, private temporary copy, fingerprinting, duplicate detection, and atomic source publication.
- [ ] 7.5 Implement archive and XML security limits for path containment, encryption/DRM, entries, expansion, compression ratios, nesting, external entities/resources, and malformed data.
- [ ] 7.6 Map publication metadata, cover, table of contents, spine, headings, paragraphs, lists, quotes, poetry, captions, notes, scene breaks, and skipped content into the structured IR.
- [ ] 7.7 Generate canonical per-chapter Markdown, stable source locators, cleanup diagnostics, and user-visible import warnings.
- [ ] 7.8 Build the import preview for metadata, ordered chapters, narration text, warnings, and storage estimate without an editing UI.
- [ ] 7.9 Demonstrate import of a known EPUB, generation of one extracted chapter, and offline playback of its verified audio.

## 8. Durable Whole-Book Generation (rough phase effort: 3–6 weeks)

- [ ] 8.1 Implement sentence/clause chunking over structured blocks with protected spans, punctuation retention, configurable pauses, and verified model limits.
- [ ] 8.2 Implement persistent project, chapter, segment, and generation-run state machines with valid transition checks and retry/error records.
- [ ] 8.3 Implement a bounded coroutine generation runner that claims work transactionally and checks pause/cancel between atomic segments.
- [ ] 8.4 Implement WorkManager scheduling, constraints, reboot/update reconciliation, and unique-work coordination over the Room queue.
- [ ] 8.5 Implement foreground generation notification with book title, segment-based progress, pause, resume, cancel, and failed-item visibility.
- [ ] 8.6 Qualify long-running WorkManager versus a user-started direct foreground service on Android 16 and select the execution host without changing queue semantics.
- [ ] 8.7 Implement audio validation, retry policy, failure categorization, and selective regeneration for a failed or stale segment.
- [ ] 8.8 Implement preflight and ongoing storage estimates with safety margins, write-failure handling, and explicit cleanup choices.
- [ ] 8.9 Add recovery tests for forced process death during inference/write/publication, device reboot, app update, low storage, and unavailable source/export storage.
- [ ] 8.10 Demonstrate a multi-chapter book resuming after forced termination without regenerating completed verified segments.

## 9. Playback (rough phase effort: 2–3 weeks)

- [ ] 9.1 Implement Media3 player and media-session service over Room-observed ready audio, keeping generation ownership outside the player.
- [ ] 9.2 Implement library and book views with cover, chapter readiness, generation progress, listening progress, failures, and storage use.
- [ ] 9.3 Implement player controls for play/pause, seek, previous/next chapter, configurable jumps, chapter selection, and playback speed.
- [ ] 9.4 Persist and restore book, chapter/segment, position, and speed across process termination and device reboot.
- [ ] 9.5 Implement media notification, lock-screen, headset/Bluetooth, audio-focus, noisy-output, and interruption behavior.
- [ ] 9.6 Update playback queues safely as new segments or chapters become ready while audio is playing.
- [ ] 9.7 Handle missing, stale, corrupt, or not-yet-ready audio with clear stop/skip and regeneration routes.
- [ ] 9.8 Demonstrate listening to completed chapters while later chapters continue generating.

## 10. Audio Encoding and Portable Export (rough phase effort: 2–4 weeks)

- [ ] 10.1 Benchmark Android AAC-LC/M4A encoding at representative mono bitrates for availability, size, duration, boundary gaps, and Serbian consonant quality against WAV.
- [ ] 10.2 Select and document the MVP bitrate, segment/chapter grouping, encoder fallback, silence insertion, and raw-PCM cleanup policy.
- [ ] 10.3 Implement verified PCM-to-M4A encoding and ensure a failed encode never replaces a ready artifact.
- [ ] 10.4 Define export-manifest schema version 1 with book, chapters, file hashes, durations, source fingerprint, generation provenance, and attribution references.
- [ ] 10.5 Implement SAF export destination selection, zero-padded collision-safe chapter names, cover/metadata writing, and explicit overwrite behavior.
- [ ] 10.6 Implement persistent per-chapter export progress, provider-aware temporary strategy, verification, retry, and destination-loss recovery.
- [ ] 10.7 Implement target and temporary storage estimates and prove export failure cannot damage the internal project.
- [ ] 10.8 Demonstrate a portable chapter-audio export that plays correctly in at least two external Android audio players.

## 11. Security, Privacy, Quality, and Recovery (rough phase effort: 2–4 weeks)

- [ ] 11.1 Add malicious EPUB tests for Zip Slip, decompression bombs, oversized entries, entity expansion, external resources, encrypted content, and malformed navigation.
- [ ] 11.2 Verify that release and F-Droid manifests have no routine network permission and that diagnostics redact document text and sensitive URIs by default.
- [ ] 11.3 Add end-to-end tests for Latin/Cyrillic equivalence, one-block invalidation, insufficient storage, corrupt model packages, corrupt audio, and disappearing SAF providers.
- [ ] 11.4 Run sustained generation-plus-playback tests on Android 11, a current Android release, Android 16, and the Poco F3 vendor battery-management configuration.
- [ ] 11.5 Add accessibility checks, Serbian/English string-resource readiness, large-text layout checks, and clear long-running/error-state UX.
- [ ] 11.6 Add an in-app diagnostics/about view for model verification, device capability, versions, licenses, attribution, storage, and redacted log export.
- [ ] 11.7 Perform a dependency and license audit, remove or replace incompatible/unmaintained dependencies, and generate bundled notices.
- [ ] 11.8 Verify every capability scenario and record unresolved deviations before declaring the MVP release candidate.

## 12. CI, Reproducibility, and Release (rough phase effort: 1–3 weeks)

- [ ] 12.1 Configure CI for formatting, static analysis, JVM tests, Android lint, golden preprocessing tests, model-package validation, and debug APK assembly.
- [ ] 12.2 Add emulator instrumentation tests for Room migrations, SAF fixtures, generation recovery, Media3 position restore, and export failure paths.
- [ ] 12.3 Pin Gradle, Android SDK/build-tools, NDK if retained, JDK, Python, ONNX, and native dependency versions with checksum or lock verification.
- [ ] 12.4 Produce source-build instructions for native/runtime artifacts and ensure no undeclared prebuilt binary is required by the F-Droid flavor.
- [ ] 12.5 Add an F-Droid-oriented build flavor and run scanner/build checks in a clean reproducible environment.
- [ ] 12.6 Generate SBOM, dependency notices, model attribution, privacy statement, threat model, benchmark report, and model-package compatibility documentation.
- [ ] 12.7 Build and verify signed GitHub release artifacts separately from optional model packages.
- [ ] 12.8 Publish the application only after legal gates, parity gates, device gates, recovery tests, export tests, and OpenSpec verification all pass.
