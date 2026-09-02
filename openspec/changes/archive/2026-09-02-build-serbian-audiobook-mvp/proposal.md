## Why

Serbian readers lack a privacy-preserving way to turn DRM-free ebooks into persistent, locally generated audiobooks using the existing Dragana Kokoro voice. The highest-risk correctness parts—the exact Serbian preprocessing pipeline and Android-compatible model export—must be proven before investing in whole-book Android workflows. On-device performance is measured early to guide optimization without blocking application development.

## What Changes

- Introduce a versioned, independently distributed Serbian Kokoro model package and prove PyTorch-to-ONNX-to-Android parity.
- Add exact Serbian Latin/Cyrillic normalization, phonemization, tokenization, safe chunking, and golden-vector validation.
- Add DRM-free EPUB import through Android's system document picker, preserving metadata, spine order, chapters, headings, and inspectable structured narration data.
- Add persistent segment-based audiobook generation with progress, pause, resume, cancel, retry, selective regeneration, storage checks, and process-death recovery.
- Add offline playback of completed audio with chapter navigation, speed control, media controls, and remembered position while later chapters continue generating.
- Add portable export as numbered chapter audio files with cover and metadata; a single M4B container remains post-MVP.
- Provide an open-source, F-Droid-compatible ARM64 Android 11+ application with no routine network dependency and no document uploads.
- Defer text-based PDF import, scanned-PDF OCR, multiple voices, in-app Markdown editing, synchronized highlighting, and DRM support.

## Capabilities

### New Capabilities

- `model-runtime`: Versioned model packaging, integrity checks, ONNX parity, Android inference, and device performance reporting.
- `serbian-text-processing`: Deterministic Serbian narration cleanup, normalization, phonemization, tokenization, chunking, and pronunciation-version tracking.
- `epub-import`: Safe DRM-free EPUB ingestion into ordered chapters and structured narration blocks.
- `durable-generation`: Persistent incremental audio generation, recovery, invalidation, progress, storage management, and error handling.
- `audiobook-playback`: Offline chapter/segment playback, media controls, progressive availability, and remembered listening position.
- `audiobook-export`: User-initiated portable chapter-audio export with metadata, cover, progress, and safe retry.

### Modified Capabilities

None.

## Impact

- Creates a new Kotlin/Compose Android application and supporting desktop model-export/parity tools.
- Adds Room, DataStore, Media3, WorkManager/foreground execution, Storage Access Framework integration, and an ONNX inference runtime.
- Introduces app-private storage for sources, structured text, models, temporary files, segment audio, and playback metadata, plus user-selected export destinations.
- Requires a dependency, training-data, model-weight, attribution, F-Droid, privacy, and reproducible-build audit before public release.
- Initial compatibility is Android 11+, `arm64-v8a`, Serbian only, with the Poco F3 as the primary benchmark device.
