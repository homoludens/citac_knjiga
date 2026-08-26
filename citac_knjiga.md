# Project Brief: Offline Serbian EPUB/PDF-to-Audiobook Android App

## Instruction for the planning LLM

Use this document as the source of truth for producing a detailed, implementation-ready project plan. Do not assume that every proposed library is the final choice. Validate the feasibility, Android support, maintenance status, licensing, APK/model size, performance, and integration complexity of important dependencies before recommending them.

The plan should be practical for an open-source project initially developed by one experienced Python/data-science/Linux developer who has less experience with modern native Android development. Prefer an incremental MVP with testable vertical slices over building all subsystems at once.

## 1. Project summary

Build an open-source Android application that imports an EPUB or PDF, extracts and cleans its contents into Markdown or a structured intermediate representation, converts the text into speech using a custom Serbian Kokoro TTS model, and produces a locally playable audiobook.

The application should work offline after the app and voice model have been installed. It should support Serbian Latin and Cyrillic text, preserve book and chapter structure when possible, generate audio reliably in the background, and allow the user to continue listening after the app or phone restarts.

The app is not merely a generic Android “read aloud” interface. Its primary purpose is to turn a document into a persistent audiobook project with chapters, generated audio, progress tracking, regeneration, and eventual export.

## 2. Existing assets and current state

The developer already has a working custom Serbian Kokoro TTS model:

- Kokoro was trained/adapted for Serbian using a Južne vesti speech dataset for Serbian language and pronunciation coverage.
- It was subsequently fine-tuned on the approximately eight-hour, single-speaker Dragana Serbian dataset to obtain a higher-quality female voice.
- The model produces intelligible Serbian speech and the Dragana fine-tuning improved voice quality.
- Some low-level noise remains in generated speech. Dataset cleaning, consistent sample rates, shorter/lower-learning-rate fine-tuning, or mild post-processing may still be needed.
- The current model is assumed to be a PyTorch checkpoint. It has not yet been proven to run correctly on Android.

Before Android integration, the project must export the model to an Android-compatible format, most likely ONNX, and compare its output against the existing PyTorch implementation using the same texts and preprocessing.

## 3. Product goals

The application should let a user:

1. Import an EPUB or PDF from Android storage through the system file picker.
2. Extract the title, author, cover, table of contents, chapters, headings, paragraphs, and useful metadata where available.
3. Convert extracted content into clean Markdown or an equivalent structured document model.
4. Preview and optionally edit chapter titles and cleaned narration text.
5. Convert Serbian text into speech locally with the custom Kokoro/Dragana voice.
6. Generate audio incrementally by paragraph, segment, or chapter.
7. Pause, resume, cancel, and recover generation jobs.
8. Play the resulting audiobook with chapter navigation, seek, playback speed, sleep timer, bookmarks, and remembered position.
9. Regenerate an individual problematic paragraph or chapter without rebuilding the whole book.
10. Keep books and generated audio on the device and operate without a server or internet connection.
11. Eventually export a finished audiobook as chapter files or a standard audiobook format.

## 4. Target platform and users

- Primary platform: Android 11 or newer.
- Primary CPU architecture for the first release: ARM64 (`arm64-v8a`).
- Initial reference device: Poco F3 or a comparable mid/high-range ARM64 Android phone.
- Initial language and voice: Serbian, using the custom female Dragana-based Kokoro voice.
- Intended users: Serbian speakers who want offline audiobooks from EPUBs and PDFs, including documents for which no commercial Serbian audiobook exists.
- The architecture should make additional compatible voices or languages possible later, but this must not complicate the first Serbian-only MVP.

## 5. Core constraints

### Offline-first and privacy

- Document extraction, Serbian preprocessing, TTS inference, audio generation, and playback should run locally.
- No document text should be uploaded by default.
- Internet access may later be used only for explicit actions such as downloading optional model packages or checking for updates.

### Open source

- The Android application will be published as open source.
- All dependencies and model/data licenses must be documented.
- Application code and voice/model files should be distributed separately until training-data and weight-redistribution rights are confirmed.
- The Dragana dataset reportedly uses CC BY 4.0 and therefore requires attribution; this must be verified.
- The licensing and permitted use of the Južne vesti training material must be investigated before public distribution of trained weights.
- If GPL code is reused or the project is based on a GPL application, the resulting licensing obligations must be explicit. A clean implementation is preferred if it gives more control and avoids unnecessary inherited scope.

### Mobile resource limits

- Large books may contain hundreds of thousands of characters and many hours of audio.
- Processing must be streaming/incremental; the app should not hold the whole book or all audio in memory.
- The system must handle limited battery, thermal throttling, Android background limits, low storage, interruptions, and process death.
- The app should estimate required generation time and storage before beginning a large job.
- Model quantization should be evaluated only after a quality-preserving baseline works.

## 6. Proposed high-level pipeline

```text
Android document picker
    -> local source copy and book fingerprint
    -> EPUB/PDF extraction
    -> Markdown or structured book representation
    -> cleanup and narration filtering
    -> Serbian text normalization
    -> Serbian phonemization/tokenization
    -> safe TTS chunks
    -> Kokoro ONNX inference
    -> audio post-processing and segment storage
    -> chapter assembly/indexing
    -> audiobook playback
    -> optional audiobook export
```

Each stage should produce persistent, inspectable output so a failure can resume from the last completed stage rather than restarting the complete book.

## 7. Recommended Android architecture to evaluate

The initial technical direction is:

| Area | Candidate technology |
|---|---|
| Language and UI | Kotlin and Jetpack Compose |
| App architecture | Unidirectional state flow, ViewModels, repositories, explicit domain layer where useful |
| Dependency injection | Hilt or a simpler alternative if appropriate |
| Project database | Room |
| Preferences | DataStore |
| Long-running generation | WorkManager plus a foreground service/notification where Android requires it |
| Playback | AndroidX Media3 |
| TTS inference | ONNX Runtime Mobile or Sherpa-ONNX; compare both |
| EPUB extraction | Native Kotlin/Java EPUB library, direct ZIP/XHTML parsing, or a Rust extractor |
| PDF extraction | Android-compatible PDF text extraction with reading-order heuristics |
| OCR, later | On-device OCR with Serbian Latin/Cyrillic support, only for scanned PDFs |
| Native/Rust integration, if used | Android NDK, Cargo NDK, and a narrow JNI interface |
| Audio encoding | Begin with simple PCM/WAV segments; evaluate AAC/M4A/Opus and eventual M4B export |
| Testing | JVM unit tests, Android instrumentation tests, golden text-normalization tests, model parity/audio tests |

AnyDoc was suggested because it converts documents to Markdown and is written in Rust. However, the planning phase must confirm whether it actually supports the needed EPUB/PDF features, compiles for Android, avoids unsupported system dependencies, and justifies JNI complexity. If it is unsuitable, propose an Android-native extraction pipeline with the same conceptual output.

## 8. Document ingestion requirements

### EPUB

EPUB should be implemented first because it normally provides structured XHTML, metadata, a table of contents, and an explicit reading order.

The importer should:

- read EPUB 2 and EPUB 3 where practical;
- follow the package spine rather than filesystem filename order;
- preserve chapter and heading hierarchy;
- extract title, author, language, cover, and table of contents;
- convert relevant XHTML elements to Markdown/structured blocks;
- remove scripts, styling, navigation duplication, repeated headings, and non-narrative boilerplate;
- define how lists, block quotes, poetry, image captions, footnotes, endnotes, tables, and links are narrated;
- keep a mapping between source locations, cleaned text, and generated audio segments when feasible.

### Text-based PDF

PDF support should follow EPUB and should be presented as best-effort because PDF stores page layout rather than semantic reading order.

The importer should attempt to:

- distinguish text PDFs from scanned/image-only PDFs;
- reconstruct paragraphs and words split across lines/pages;
- remove recurring headers, footers, and page numbers;
- handle common one-column layouts first;
- detect multi-column or badly ordered documents and warn the user;
- let the user preview and correct chapter boundaries and extracted text.

### Scanned PDF/OCR

OCR is out of scope for the first MVP. Later, the app may add on-device OCR for Serbian Latin and Cyrillic, page rotation, deskewing, language selection, confidence warnings, and correction before speech generation.

## 9. Structured intermediate representation

Markdown should be retained because it is easy to inspect, edit, export, and debug. It should not be the only internal representation. The app should parse it into structured narration blocks.

Conceptual entities:

```kotlin
data class BookProject(
    val id: String,
    val title: String,
    val author: String?,
    val sourceUri: String,
    val sourceFingerprint: String,
    val coverPath: String?,
    val language: String,
    val status: ProjectStatus
)

data class Chapter(
    val id: String,
    val bookId: String,
    val order: Int,
    val title: String,
    val sourceMarkdown: String,
    val narrationText: String
)

data class NarrationSegment(
    val id: String,
    val chapterId: String,
    val order: Int,
    val sourceText: String,
    val normalizedText: String,
    val pronunciationOverridesVersion: Int,
    val status: SegmentStatus,
    val audioPath: String?,
    val durationMs: Long?,
    val error: String?
)
```

The detailed plan should improve this data model, define Room entities and migrations, and specify which large text/audio data belongs in files versus the database.

## 10. Serbian text processing: critical correctness requirement

The Android inference pipeline must reproduce the successful training/inference preprocessing exactly. A correct ONNX model with different text normalization, phonemization, token IDs, punctuation handling, or style embeddings can sound wrong.

The processing chain is:

```text
original Serbian text
    -> cleanup for narration
    -> script-aware normalization
    -> expansion of numbers and abbreviations
    -> Serbian phonemization/G2P
    -> exact Kokoro tokens and IDs
    -> model inference with Dragana voice/style
```

It must account for:

- Serbian Latin and Cyrillic input;
- letters `č`, `ć`, `š`, `ž`, and `đ`;
- Latin digraphs `lj`, `nj`, and `dž` where relevant to the tokenizer/G2P;
- mixed scripts and foreign names;
- cardinal and ordinal numbers with grammatical limitations documented;
- dates, years, times, decimals, currencies, percentages, measurements, phone numbers, and ranges;
- Roman numerals;
- common abbreviations such as `dr`, `prof.`, `npr.`, and `itd.`;
- punctuation, quotations, parentheses, dashes, ellipses, and paragraph pauses;
- URLs, email addresses, citations, references, footnote markers, and page artifacts;
- user-defined pronunciation replacements.

The existing desktop preprocessing code must be treated as the reference implementation. The plan should include a way to generate golden test cases on desktop and verify identical or intentionally equivalent Android outputs.

## 11. TTS model integration

The first milestone is not the complete app. It is a desktop-to-Android model parity proof.

Required work:

1. Document the current PyTorch model inputs, outputs, sample rate, tokenizer, phonemizer, voice/style representation, supported chunk length, and inference parameters.
2. Export the model to ONNX with fixed or dynamic shapes as appropriate.
3. Package all required artifacts, for example:

```text
serbian-kokoro-dragana/
├── model.onnx
├── tokens.txt
├── voice/style data or embedded constants
├── model-config.json
├── normalization resources
├── test-vectors/
└── LICENSES-and-ATTRIBUTION.md
```

4. Validate ONNX on desktop against PyTorch using a representative Serbian test suite.
5. Run the same model and test vectors in a minimal Android proof-of-concept screen.
6. Measure latency, real-time factor, peak RAM, model load time, app/model size, CPU utilization, temperature, and battery impact.
7. Establish an FP32 or FP16 quality baseline before testing INT8 or other quantization.
8. Determine whether ONNX Runtime Mobile or Sherpa-ONNX provides the cleaner and more maintainable integration.

The model package should be independently versioned. The app database must record which model, normalization rules, pronunciation dictionary, and inference settings generated every audio segment so stale segments can be detected after upgrades.

## 12. Chunking and audio generation

Text must be split at linguistically safe boundaries while respecting the model’s input limits.

Preferred behavior:

- split by chapter, paragraph, sentence, and finally clause only when required;
- never split in the middle of an abbreviation, decimal, name, or token sequence;
- retain enough punctuation to produce natural prosody;
- generate segments independently and concatenate only at playback/export boundaries;
- add configurable pauses between sentences, paragraphs, and headings;
- use deterministic segment identifiers or hashes for caching;
- atomically write completed audio so interrupted segments are not mistaken for valid output;
- detect invalid outputs such as silence, NaN values, clipping, or implausible duration;
- support retrying failed segments;
- allow individual segment regeneration after a text or pronunciation change.

Mild optional post-processing may include high-pass filtering, loudness normalization, limiting, and carefully tested noise reduction. It must not damage Serbian consonants such as `s`, `š`, `ž`, `č`, and `ć`. Raw and processed comparisons should be retained during development.

## 13. Background work and recovery

Audiobook generation may take minutes or hours, so the job system must be durable.

The design should include:

- a persistent queue of segment jobs;
- a clear project/chapter/segment state machine;
- foreground notification with book title, progress, pause, resume, and cancel actions;
- recovery after process death, reboot, app update, low battery, or lost storage access;
- idempotent workers so completed segments are not regenerated unnecessarily;
- configurable conditions such as “only while charging” and optional thermal/battery safeguards;
- accurate progress based on segments or estimated text duration, not only chapters;
- understandable error reporting and a list of failed segments;
- safe cleanup of temporary and orphaned files.

The plan must explain the division of responsibility between WorkManager, foreground services, coroutines, Room, and Media3 rather than simply listing them.

## 14. Playback requirements

The first useful player should provide:

- book library and cover display;
- chapter list and current chapter;
- play/pause, seek, previous/next chapter, and jump backward/forward;
- playback speed;
- remembered listening position per book;
- media notification and lock-screen controls;
- audio focus, headset/Bluetooth events, and interruption handling;
- ability to play completed chapters while later chapters are still generating.

Later features may include bookmarks, sleep timer, synchronized text highlighting, search, pronunciation issue reporting, and Android Auto compatibility.

## 15. Storage and export

The app should keep source documents, extracted Markdown, project metadata, model packages, temporary audio, and final audio in clearly separated locations.

The design must address:

- Storage Access Framework permissions and persisted URIs;
- what is stored in app-private storage versus a user-selected export directory;
- estimated and actual disk usage;
- cleanup choices that do not destroy the original document without confirmation;
- model download/import/update and version coexistence;
- backup/restore limitations;
- chapter audio export in an initially simple format;
- eventual M4B or another chapter-capable audiobook export, including cover and metadata.

For the MVP, reliable internal playback of separate chapter/segment files is more important than M4B export.

## 16. User experience outline

Suggested main screens:

1. **Library** — imported books, cover, status, generation progress, listening progress, storage use.
2. **Import** — choose EPUB/PDF, inspect metadata, preview extracted chapters, show extraction warnings.
3. **Book project** — chapter list, generation state, failed items, generate/pause/resume, edit narration text.
4. **Player** — standard audiobook controls and chapter navigation.
5. **Voice/model settings** — installed model, version, quality/speed mode, pauses, output options.
6. **Pronunciation dictionary** — user replacements, preview, and regeneration impact.
7. **Diagnostics/about** — device capability, licenses, attribution, logs export, model verification status.

The MVP UI should remain simple and make long-running or failed operations obvious.

## 17. MVP scope

### Required for MVP

- ARM64 Android 11+.
- Serbian-only UI support may begin in English, but Serbian text and TTS must work correctly.
- Import DRM-free EPUB.
- Extract metadata, spine order, chapters, headings, and narration text.
- Save extracted Markdown/structured data.
- Run the custom Dragana Kokoro model offline on Android.
- Correct Serbian Latin and Cyrillic preprocessing using the reference pipeline.
- Generate audio incrementally and recover after interruption.
- Show chapter and overall progress, errors, pause/resume/cancel.
- Play generated chapters with Media3 and remember position.
- Regenerate individual failed or edited segments.
- Basic disk-space estimate and cleanup.
- Licenses and required attribution included.

### After MVP

- Text-based PDF import.
- Advanced PDF cleanup and multi-column handling.
- On-device OCR for scanned PDFs.
- M4B/chaptered export.
- Multiple voices and model downloader.
- Quality/speed quantized model options.
- Text highlighting synchronized to playback.
- Pronunciation editor with preview.
- Bookmarks, sleep timer, Android Auto, and broader device architectures.

### Explicitly out of scope initially

- DRM removal.
- Cloud conversion or hosted TTS.
- Perfect extraction of arbitrary PDFs.
- Training models on the phone.
- Voice cloning in the Android app.
- A social/catalog service or account system.
- iOS and desktop versions.

## 18. Quality requirements and acceptance criteria

The detailed plan should turn these into measurable tests. At minimum:

- A representative EPUB imports with chapters in correct spine order.
- Latin and Cyrillic versions of equivalent Serbian test sentences produce equivalent pronunciation.
- Android preprocessing matches the documented reference test vectors.
- ONNX output is acceptably close to the PyTorch baseline; the exact audio/spectral criteria must be defined.
- A multi-chapter book can resume generation after forced process termination without losing completed work.
- The user can listen to already completed chapters while generation continues.
- Editing one paragraph invalidates and regenerates only the affected audio.
- The app detects insufficient storage before generation and handles write failures without corrupting the project.
- Playback position survives app restart and device reboot.
- No document content leaves the device during normal use.
- Open-source and model/data attribution requirements are visible and packaged correctly.

## 19. Important risks and unknowns

The planning LLM must explicitly investigate or schedule spikes for:

1. **Model export:** whether all Kokoro operations, style inputs, and preprocessing export cleanly to ONNX.
2. **Serbian preprocessing:** whether the current phonemizer depends on Python/eSpeak behavior that is difficult to reproduce exactly on Android.
3. **Performance:** acceptable real-time factor, heat, battery consumption, and memory use on the Poco F3 and lower-end phones.
4. **Noise:** whether noise originates in training data, resampling, over-fine-tuning, vocoder behavior, or inference/post-processing.
5. **AnyDoc feasibility:** whether compiling and embedding it is worthwhile versus native EPUB/PDF parsing.
6. **Android background execution:** reliable behavior across Android versions and aggressive vendor battery management.
7. **Audio format:** trade-offs among WAV simplicity, storage use, encoding availability, gapless playback, and export.
8. **Licensing:** right to distribute weights derived from Južne vesti; confirmed Dragana attribution terms; compatibility of all code dependencies.
9. **PDF complexity:** realistic support boundaries and user-facing warnings.
10. **Model distribution size:** bundled model versus separate download/import, update strategy, checksums, and offline installation.

## 20. Expected output from the planning LLM

Create an implementation-ready plan containing:

1. Recommended architecture and justified technology choices, including alternatives rejected and why.
2. A repository/module structure for the Android app, native components, model tools, and tests.
3. A refined Room/database schema and generation state machines.
4. Detailed data flow from document import through extraction, normalization, TTS, storage, and playback.
5. A model export and parity-validation plan before full app development.
6. A Serbian preprocessing port and golden-test strategy.
7. An EPUB MVP design, followed by a separate PDF/OCR roadmap.
8. Background processing and failure-recovery design.
9. Audio file, caching, storage, and export strategy.
10. Security/privacy considerations for untrusted EPUB/PDF input and local file handling.
11. Dependency and licensing audit checklist.
12. Test strategy: unit, integration, instrumentation, performance, audio quality, recovery, and representative book fixtures.
13. CI/CD and reproducible build recommendations for an open-source GitHub repository.
14. Phased milestones, each ending in a runnable demonstration with clear acceptance criteria.
15. A prioritized task backlog with dependencies and rough effort ranges, avoiding false precision.
16. The smallest first vertical slice that proves the highest-risk assumptions.
17. Questions that must be answered from the current Kokoro training repository before implementation begins.

The first proposed milestone should ideally prove this complete path with typed Serbian text before adding document import:

```text
Kotlin text input
    -> exact Serbian normalization/tokenization
    -> Kokoro ONNX on Android
    -> playable generated audio
```

The second vertical slice should add a small known EPUB and generate/play one extracted chapter. Only after those two slices are stable should the plan expand to resilient whole-book generation and PDF support.

## 21. Questions for the project owner

The detailed plan may need answers to the following, but it should make reasonable provisional assumptions so planning can proceed:

- kokoro 0.9.4
- What are the exact model inputs, output sample rate, voice/style files, tokenizer, phonemizer, and text-normalization code?
- voice is in kokoro_sr_dragana_voice folder

- serbian only for now

- Must the first release export a portable audiobook, or is reliable in-app playback sufficient?
- export audiobook

- Should users be able to edit extracted Markdown inside the app in the MVP?
- no


- Is F-Droid distribution a goal in addition to GitHub releases/Google Play?
- yes.
