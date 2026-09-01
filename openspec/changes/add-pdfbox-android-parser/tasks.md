## 1. Isolated Qualification

- [ ] 1.1 Create an isolated Android qualification consumer for the pinned PdfBox-Android candidate without adding it to the production Gradle graph, and verify the consumer resolves and builds offline after dependencies are cached.
- [ ] 1.2 Initialize PdfBox's Android resources in the qualification consumer and add the existing PDF fixture corpus, and verify page-count and basic text extraction run on the Android test target.
- [ ] 1.3 Implement the qualification text-position collector and fixture assertions for page selection, Serbian Latin/Cyrillic, Unicode, coordinates, separated columns, and overlapping text, and verify the candidate produces the required structured evidence.
- [ ] 1.4 Add qualification cases for encrypted, password-protected, malformed, truncated, unsupported, image-only, and externally referenced PDFs, and verify failures are closed, OCR is not claimed, and no external resolver or network sentinel is invoked.
- [ ] 1.5 Measure cancellation, deadline, memory, processing time, APK delta, API compatibility, dependency/source closure, and license obligations, and verify the binary qualification report records a passing or failing result without selecting an unqualified candidate.

## 2. Production Dependency

- [ ] 2.1 If and only if qualification passes, add the exact PdfBox-Android coordinate and transitive versions to the version catalog and `document-pdf`, and verify dependency locking, checksum verification, and offline production builds.
- [ ] 2.2 Record Apache PDFBox, PdfBox-Android, and Bouncy Castle attribution and update source-closure and dependency-license inventories, and verify release notice and closure checks pass.
- [ ] 2.3 Add centralized Android resource-loader initialization to the production composition root and test setup, and verify application startup and parser instrumentation complete without initialization errors.

## 3. Parser Adapter

- [ ] 3.1 Implement the production `PdfPageImporter` adapter over the staged private file, including page-count inspection and inclusive selected-page inspection, and verify the adapter returns only requested page numbers.
- [ ] 3.2 Collect PdfBox text positions per page and convert page rotation, crop-box, and glyph bounds into normalized top-left rectangles, and verify coordinates remain within `NormalizedRect` bounds on rotated and non-standard pages.
- [ ] 3.3 Group collected positions into stable text blocks with whitespace normalization and existing narration block types, and verify canonical output preserves Serbian text, Unicode, page order, and block locators.
- [ ] 3.4 Map protected, malformed, truncated, unsupported, unreadable, image-only, and external-reference cases to existing diagnostics without returning partial text, and verify the parser unit and Android fixture tests cover each failure class.
- [ ] 3.5 Add deadline and coroutine-cancellation checks around page and position processing while honoring the existing byte and text limits, and verify timeout and cancellation tests remove temporary parser state and publish nothing.

## 4. Application Integration

- [ ] 4.1 Construct the PDF source repository, parser, preview service, canonical text service, and acceptance service in `AppContainer.production`, and verify a staged fixture reaches the parser instead of `UnavailablePdfPageImporter`.
- [ ] 4.2 Add the local PDF picker, page-count/range fields, loading, preview, diagnostics, cancellation, discard, and acceptance states beside EPUB import in `StartScreen`, and verify Compose tests reject invalid ranges and show preview text before acceptance.
- [ ] 4.3 Wire accepted PDF previews through `PdfDocumentProjector` and `PdfAcceptanceService` into existing project, chapter, and narration-block persistence, and verify no generation job or audio is created during acceptance.
- [ ] 4.4 Enable `PdfFeatureAvailability` only from a passing qualification result and preserve the unavailable diagnostic on any failed gate, and verify a no-pass build cannot expose a usable PDF import path.

## 5. Verification And Release

- [ ] 5.1 Add JVM coverage for text normalization, geometry ordering, limits, provenance, diagnostics, and atomic rollback, and verify `./gradlew :document-pdf:testDebugUnitTest` passes.
- [ ] 5.2 Add Android instrumentation for real PdfBox extraction, provider disappearance after staging, cancellation, malformed/protected files, external-resource isolation, persistence, and PDF UI states, and verify the affected Android test tasks pass on the available API targets.
- [ ] 5.3 Run lint, dependency, source-closure, notice, offline-build, and release APK checks with the parser enabled, and verify the release artifact contains no OCR model, PDF upload path, or undeclared dependency.
- [ ] 5.4 Update `DEPLOYMENT.md` with PdfBox dependency, qualification, verification, and rollback steps and update `AGENT_README.md` with the selected parser and current gate evidence, and verify both documents match the checked-in release evidence.
