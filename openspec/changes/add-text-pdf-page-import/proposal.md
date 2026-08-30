## Why

Users need a bounded way to import selected pages from born-digital PDFs into the existing offline audiobook workflow. PDF text extraction is inherently less reliable than EPUB structure, so users must be able to validate the page range, inspect extracted text, and understand unsupported or uncertain layouts before accepting an import.

## What Changes

- Add local selection of a text PDF and one contiguous inclusive page range, with page-count display and range validation.
- Extract the selected pages and show an ordered-text preview, warnings, and blocking errors before the user accepts the import.
- Preserve PDF page provenance while converting accepted text into the existing ordered chapter and narration-block pipeline; do not create a parallel audiobook pipeline.
- Detect image-only or scanned selected pages and clearly report that OCR is unsupported.
- Warn when multi-column layout or unreliable reading order makes extraction unsupported or uncertain.
- Enforce bounded file size, page count, selected-page count, extracted-text size, parser resource, and processing-time limits, and never fetch external resources.
- Keep the Android PDF parser choice open for qualification in design based on extraction quality, safety, maintenance, licensing, size, and integration cost.
- Non-goals: OCR, arbitrary multiple disjoint ranges, complex layout reconstruction, PDF editing, and network resource loading.

## Capabilities

### New Capabilities

- `text-pdf-page-import`: Safe, bounded import of one contiguous page range from a born-digital PDF, with pre-import validation and preview, page provenance, extraction diagnostics, and conversion into the existing structured narration pipeline.

### Modified Capabilities

None. There are currently no canonical capability specs under `openspec/specs` to modify.

## Impact

- Affects the Android document-import UI, local PDF inspection and text extraction, import diagnostics, and persistence of page-level source provenance.
- Reuses the existing project, chapter, narration-block, generation, playback, and export flow after the user accepts the preview.
- Requires representative fixtures and tests for valid ranges, extraction order, scanned pages, multi-column warnings, malformed PDFs, limit enforcement, cancellation or failure cleanup, and blocked external resources.
- Adds no OCR, network access, PDF editor, or separate generation subsystem; the parser dependency is selected only after design qualification.
