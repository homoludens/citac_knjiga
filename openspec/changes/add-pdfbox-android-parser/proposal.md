## Why

PDF import is currently fail-closed because the project has no production parser, so users cannot turn born-digital PDFs into audiobook content. PdfBox-Android provides an Android-native, Apache-licensed parser with page and text-position APIs that can be evaluated and adapted to the existing PDF import contract.

## What Changes

- Add the pinned `com.tom-roush:pdfbox-android` dependency with Gradle locking, verification metadata, and required Apache/Bouncy Castle notices.
- Implement a `PdfPageImporter` adapter that reads only the privately staged PDF, reports page count, and extracts selected pages into text blocks with normalized geometry.
- Preserve the existing page-range limits, Serbian/Unicode normalization, deterministic layout ordering, provenance, diagnostics, cancellation, deadline, and atomic cleanup behavior.
- Qualify PdfBox-Android against the existing PDF fixtures on supported Android API levels, including text fidelity, multi-column ordering, protected or malformed files, external-resource isolation, resource limits, and performance.
- Replace the unavailable PDF parser gate only after qualification passes, enabling the existing PDF preview, acceptance, and downstream narration flow.
- Keep PDF files local during parsing. Network access for explicit model-package downloads remains separate and is not used to upload PDFs or resolve PDF resources.

## Capabilities

### New Capabilities

- `pdfbox-android-parser`: Local Android PDF parsing that produces bounded page text, positioned text blocks, diagnostics, and provenance for the existing PDF import pipeline.

### Modified Capabilities

None. The existing `text-pdf-page-import` specification is part of an unarchived change, not a canonical specification under `openspec/specs`.

## Impact

- Affects the `document-pdf` module, its parser boundary, PDF qualification consumer, fixtures, and Android instrumentation tests.
- Adds PdfBox-Android and its transitive dependencies to the production Gradle graph and release notices.
- Enables the existing PDF import UI and projection into `DocumentIr`, canonical Markdown, narration blocks, and the current generation flow after parser qualification.
- Does not add OCR, a remote PDF service, a parallel audiobook pipeline, or a model package to the APK.
