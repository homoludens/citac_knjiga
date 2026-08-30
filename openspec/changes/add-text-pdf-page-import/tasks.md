## 1. Isolated Parser Qualification

- [ ] 1.1 Create a disposable qualification consumer outside production dependency wiring, with candidate adapters for AndroidX PDF, PDFBox-Android, and viable existing or platform APIs, and verify the production Gradle graph contains none of these candidates.
- [ ] 1.2 Add small redistributable qualification fixtures for Latin and Cyrillic Unicode text, soft wrapping, one-column text, separated columns, overlapping columns, empty pages, image-only pages, repeated decoration, protected files, malformed/truncated files, unsupported encodings, and external references, and verify the spike loads every fixture from local test resources.
- [ ] 1.3 Run the identical candidate fixture suite on API 30, API 35, and API 36 for page count, text fidelity, page boundaries, block geometry, reading order, ambiguous layouts, failure-closed behavior, cancellation, and deadline handling, and verify the matrix report contains pass/fail evidence for every candidate and API.
- [ ] 1.4 Measure each candidate's embedded/external-resource behavior, memory and time under the import profile, API stability, integration cost, APK delta, upstream maintenance, and complete transitive source/license/notice closure including F-Droid source-build constraints, and verify the report proves no URI, file action, or external resource is opened.
- [ ] 1.5 Produce a binary qualification report and preserve fixture outputs, measurements, dependency-locking, verification, and offline-build evidence, and verify no candidate is marked selected unless it passes every required gate and the no-pass result leaves PDF import unavailable.

## 2. Production Dependency And Core Contracts

- [ ] 2.1 If and only if qualification passes, pin the selected parser coordinates, checksums, lock entries, verification metadata, and notices using existing Gradle conventions; otherwise keep production parser dependencies and the PDF feature disabled, verified by the qualification gate and release dependency-graph test.
- [ ] 2.2 Define the format-neutral `DocumentIr`, `DocumentChapter`, `DocumentBlock`, `ImportProvenance`, `PageLocator`, `ImportWarning`, and `ImportDiagnostic` contracts with canonical fingerprint/page/block locators, and verify JVM unit tests assert stable locator rendering and ordered serialization.
- [ ] 2.3 Define one immutable production limits profile for 512 MiB source bytes, 10,000 pages, 200 selected pages, 1 MiB normalized UTF-8 bytes per page, 32 MiB per range, and 120 seconds, and verify a unit test accepts equality and rejects the first byte/page/count beyond each bound.
- [ ] 2.4 Define the PDF source, page range, inspection, adapter, and non-networking resource-policy interfaces so adapters receive only a staged private file plus deadline/cancellation controls, and verify contract tests expose no `ContentResolver`, URL resolver, network client, or PDF-derived filesystem path.

## 3. Staging, Fingerprinting, And Bounded Inspection

- [ ] 3.1 Implement SAF PDF validation and owner-scoped private staging that copies bytes once while calculating lowercase SHA-256 and size, rejects over-limit input at byte 536,870,913, and records URI only as provenance, verified by source-repository tests observing the partial-file deletion.
- [ ] 3.2 Enforce canonical private-path, size, and fingerprint rechecks before page count, extraction, preview, and acceptance, and verify a source replacement or provider disappearance fails closed without reopening the SAF URI.
- [ ] 3.3 Implement page-count inspection before range validation, reject unreadable/encrypted/password-protected/malformed/truncated/unsupported PDFs without partial text, and verify parser and service tests return stable safe-failure diagnostics with no publication.
- [ ] 3.4 Implement monotonic `System.nanoTime()` deadlines created at page-count inspection and checked before and after parser, page, normalization, and preview work, and verify a fake slow adapter reports the timeout diagnostic and publishes nothing.
- [ ] 3.5 Thread coroutine cancellation through staging, page counting, extraction, normalization, preview, and discard as terminal cleanup, and verify cancellation tests remove owner temporary state and leave no project or accepted document.
- [ ] 3.6 Enforce per-page and range counters using exact normalized UTF-8 byte counts, inspect only selected page text, and fail on the first exceeded limit, verified by boundary tests that avoid allocating multi-gigabyte inputs.

## 4. Page Range And Extraction

- [ ] 4.1 Implement the 1-based inclusive `PageRange` validator requiring `1 <= start <= end <= pageCount` and at most 200 pages, and verify tests reject empty, reversed, zero-based, out-of-bounds, and disjoint input before adapter extraction.
- [ ] 4.2 Implement the qualified parser adapter that returns page count, selected pages, text spans/blocks with normalized page geometry, and source provenance without resolving hyperlinks, annotations, embedded files, file actions, or external references, and verify adapter tests observe only staged-byte reads.
- [ ] 4.3 Normalize line endings and layout whitespace deterministically while trimming only boundary whitespace and preserving soft-break characters, discretionary hyphens, headers, footers, page numbers, and other source content, and verify normalization tests compare exact expected UTF-8 output.
- [ ] 4.4 Implement the fixed versioned layout profile with the 8% gutter rule, largest-gap/leftmost-tie selection, at most two columns, top-to-bottom column ordering, and provable left-to-right same-row ordering, and verify geometry tests produce deterministic block order across repeated runs.
- [ ] 4.5 Emit page-ordered `MULTI_COLUMN` warnings for provably separated columns and block acceptance for overlapping, crossing, incompatible, or otherwise unprovable order with `UNRELIABLE_LAYOUT`, and verify diagnostics are ordered by page then block and block uncertain text is never silently presented as reliable.
- [ ] 4.6 Detect image-only pages with no extractable text as blocking `OCR_UNSUPPORTED`, distinguish empty non-image pages with an empty-page diagnostic, and fail closed when the adapter cannot distinguish them, verified by fixtures asserting no OCR code/model/confidence and no empty narration content.
- [ ] 4.7 Ignore or report remote hyperlinks, external file actions, annotations, embedded-file references, and other external URIs without opening them, and verify hostile-resource tests use a failing resolver/network sentinel that is never invoked.

## 5. IR Projection And Persistence

- [ ] 5.1 Convert each selected narratable page into one ordered existing document chapter titled with qualified metadata or `Page <number>`, retain ordered narration blocks, and preserve fingerprint plus canonical 1-based page/block locators, verified by projection unit tests for pages 3-5.
- [ ] 5.2 Reuse the existing project, chapter, narration-block, Markdown, and downstream generation representation without adding a PDF-specific audiobook pipeline, and verify accepted-import tests find existing pending records and no generation run, audio segment, audio file, or TTS invocation.
- [ ] 5.3 Render deterministic UTF-8 canonical Markdown and per-page artifacts from the IR through `AtomicArtifactStore`, and verify golden-output tests retain page provenance and stable ordering after reopening without the original SAF URI.
- [ ] 5.4 Add the format-specific durable PDF source accessor at `sources/<projectId>/source.pdf` and map fingerprint, private source path, original URI provenance, chapter locators, and block locators to existing columns, verified by persistence tests with no Room migration or parallel PDF table.

## 6. Preview And StartScreen UI

- [ ] 6.1 Add the local `application/pdf` SAF picker beside the existing EPUB import on `StartScreen`, keep validation authoritative beyond the advisory filter, and verify Compose instrumentation launches the picker and rejects a non-PDF selection without staging it.
- [ ] 6.2 Add PDF UI state for staged source, total 1-based page count, two range fields, validation errors, loading, preview, failure, cancellation, and discard, and verify UI tests show the count before extraction and prevent extraction for invalid or disjoint ranges.
- [ ] 6.3 Display every selected page separately with its locator, ordered extracted text, warnings, and blocking diagnostics, and verify preview instrumentation shows pages 3-5 in order and disables acceptance whenever a blocking diagnostic exists.
- [ ] 6.4 Wire cancellable PDF preview and acceptance callbacks through `AppContainer` and `MainActivity` without navigation-route or `BookRoute` changes, and verify cancellation from loading and preview removes temporary state and creates no project or generation job.
- [ ] 6.5 Provide actionable localized formatting for range, limit, protected/malformed, OCR unsupported, unreliable layout, and external-resource diagnostics without source-text or full-URI leakage, and verify app tests assert the rendered messages and redaction.

## 7. Atomic Acceptance And Cleanup

- [ ] 7.1 Verify staged ownership, fingerprint, size, selected range, parser result, source text, page artifacts, and warning report before acceptance, and verify a changed staged source or preview result is rejected before any candidate publication.
- [ ] 7.2 Publish only new project-ID canonical/source artifacts through `AtomicArtifactStore`, then insert the complete project, ordered chapters, and blocks in one Room transaction, and verify a successful integration test observes all rows together and no visible partial state.
- [ ] 7.3 Roll back candidate files and the Room transaction on write, fingerprint, indexing, or transaction failure while preserving existing projects, and verify failure injection leaves no new project/chapter/block, source, canonical text, diagnostic report, or generation job.
- [ ] 7.4 Delete owner-scoped source, extracted text, diagnostics, and temporary canonical output on cancellation, duplicate, parser failure, timeout, range failure, discard, and terminal acceptance failure without following source-provided names or URIs, and verify cleanup tests leave unrelated files untouched.
- [ ] 7.5 Integrate startup orphan reconciliation for aged unreferenced PDF candidate files while protecting Room-referenced sources and existing project files, and verify process-death instrumentation cleans only eligible owner artifacts.

## 8. Fixtures And Verification Gates

- [ ] 8.1 Add redistributable PDF fixtures and generated boundary cases for valid text, Serbian Latin/Cyrillic, Unicode, soft wrapping, separated/overlapping columns, repeated decoration, empty/image-only pages, protected files, malformed/truncated structure, external references, and unsupported encodings, and verify fixture tests assert the specified diagnostics and preserved content.
- [ ] 8.2 Add JVM coverage for limits, UTF-8 counters, deadlines, cancellation, range validation, normalization, geometry ordering, diagnostics, external-resource isolation, IR projection, and atomic rollback, and verify `./gradlew :document-pdf:testDebugUnitTest` passes or the repository's resolved PDF module test task passes.
- [ ] 8.3 Add Android instrumentation for API 30/35/36 qualification, SAF staging followed by provider disappearance, private-path enforcement, cancellation, process-death cleanup, Room transaction isolation, and Compose picker/range/preview/acceptance states, and verify the affected connected Android test tasks pass.
- [ ] 8.4 Run release checks for dependency/source closure, offline reproducibility, lint, unit tests, instrumentation compilation, and arm64 APK size, and verify the release gate passes with no unqualified parser dependency, OCR surface, network-loading behavior, disjoint-range support, or parallel generation pipeline.
