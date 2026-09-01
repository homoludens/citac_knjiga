## Context

See `proposal.md` for the motivation and `specs/pdfbox-android-parser/spec.md`
for the behavior contract. The `document-pdf` module already owns private PDF
staging, page-range validation, normalization, geometry ordering, provenance,
canonical Markdown, and projection into the shared document pipeline. Its
`PdfImportPreviewService` currently defaults to an unavailable parser because
the qualification report has no selected implementation. The app composition
root currently wires EPUB import but no PDF importer.

## Goals / Non-Goals

**Goals:**

- Add an Android-native parser adapter that supplies page text positions to the
  existing PDF inspection and projection code.
- Keep parser reads bounded to the staged file and preserve the existing
  fail-closed import, cleanup, and provenance behavior.
- Qualify the parser on Android before making it a production dependency.
- Expose accepted PDF pages through the existing `DocumentIr`, canonical text,
  narration, and generation flow.

**Non-Goals:**

- OCR, PDF rendering for editing, PDF mutation, or remote PDF conversion.
- Replacing the existing layout profile with a parser-specific ordering policy.
- Adding a PDF-specific database, audiobook pipeline, or model package.
- Downloading or uploading PDFs. Network-based model-package acquisition is a
  separate concern.

## Decisions

### Parser adapter and lifecycle

Implement a PdfBox-backed `PdfPageImporter` in `document-pdf`. The adapter will
open only `StagedPdfSource.sourceFile`, never its source URI, and will close the
document on every success and failure path. The application composition root
will initialize PdfBox's Android resource loader with the application context
before constructing the importer. Tests will perform the same initialization
in their Android setup.

Page-count inspection will read the document page tree and return the count
without producing text. Range inspection will process only the requested
1-based pages and will return the existing `PdfPage` shape. The preview service
will remain responsible for staging, fingerprint checks, range validation,
deadline ownership, cleanup, and acceptance validation.

### Text and geometry extraction

Use a small subclass of PdfBox text processing that collects `TextPosition`
values per page instead of relying on the library's final plain-text output.
The collector will retain Unicode text, glyph bounds, page dimensions, page
rotation, and crop-box adjustments. It will group adjacent positions into
lines and paragraph-like blocks, estimate only layout whitespace, and emit
`DocumentBlock` values with stable page/block locators. PDF positions will be
converted to the normalized top-left coordinate system expected by
`NormalizedRect`.

The adapter will not treat content-stream order or PdfBox's `sortByPosition`
result as the final reading order. `PdfPageInspector` and the versioned
project layout profile will continue to classify columns, overlaps, warnings,
and blocking ambiguity. A page with no collected text will be classified using
the existing image-only/empty-page path; no OCR fallback will be added.

### Resource and security boundary

The adapter will check `PdfInspectionControls.deadline` and coroutine
cancellation between pages and while building position groups. Existing byte,
page, range, and text counters remain authoritative; the adapter will stop
before returning a result that exceeds them. Parser exceptions will be mapped
to the existing protected, malformed, unsupported, or timeout diagnostics.

Only local PDF objects needed for page text, geometry, and safe image/reference
classification may be inspected. Link actions, annotations, embedded files,
remote URLs, and external file specifications will be recorded or ignored as
metadata without resolving or opening them. No `ContentResolver`, network
client, or path derived from PDF content will be passed below the adapter
boundary.

### Qualification before production wiring

Extend the disposable qualification consumer with an Android Gradle test
consumer that depends on the candidate PdfBox-Android artifact. It will run
the existing fixture corpus and record page count, Serbian text fidelity,
Unicode handling, page selection, text positions, column behavior, protected
and malformed-file behavior, external-resource isolation, cancellation,
deadlines, memory, processing time, APK delta, and license/source closure.

The candidate must pass the required Android API matrix and offline build
checks before it is selected. Only then will the exact Maven coordinate be
added to the version catalog and `document-pdf` dependency list, with lockfile,
verification metadata, source closure, and Apache/Bouncy Castle notices. A
failed or incomplete qualification keeps the unavailable adapter and the PDF
feature gate disabled.

### Application integration

Add a PDF repository, parser, preview service, and acceptance service to
`AppContainer.production`. Add the PDF picker and range/preview states as a
sibling of the current EPUB import surface in `StartScreen`; the picker remains
an advisory `application/pdf` filter and staging remains authoritative.
Acceptance will use `PdfDocumentProjector` and `PdfAcceptanceService`, then
leave chapters and narration blocks pending for the existing explicit
generation action. No navigation route or `BookRoute` changes are required.

## Risks / Trade-offs

- [PdfBox-Android is based on an older PdfBox line] -> Pin the tested artifact,
  record maintenance and vulnerability review in qualification evidence, and
  keep the parser behind the production gate.
- [Glyph grouping and whitespace reconstruction can misread unusual PDFs] ->
  retain coordinates, use deterministic thresholds, show multi-column warnings,
  and block ambiguous layouts rather than trusting plain-text output.
- [A malformed or hostile PDF may consume excessive memory or block inside the
  parser] -> Enforce staged-input and output limits, check deadlines around
  parser work, test large and malformed fixtures, and publish nothing until the
  complete inspection succeeds.
- [PdfBox transitive crypto dependencies can increase APK size or conflict with
  existing dependencies] -> Measure release APK impact, lock exact versions,
  run dependency/source-closure checks, and publish all required notices.
- [Android resource-loader initialization may be missed in tests or alternate
  composition paths] -> Centralize initialization in the production composition
  root and provide an explicit test setup helper.
- [The parser may inspect external PDF objects unexpectedly] -> Supply only the
  staged file, avoid resolver APIs, add failing network/file sentinels to tests,
  and inspect the adapter's reachable calls before enabling the gate.

## Migration Plan

1. Build and run the isolated PdfBox qualification consumer without changing
   the production dependency graph.
2. If all gates pass, add the pinned dependency, adapter, notices, verification
   data, app composition, PDF UI, and Android tests.
3. Enable the parser gate and verify preview, acceptance, persistence, and
   downstream generation with representative fixtures.
4. If the parser fails after release, set the gate back to unavailable and
   remove only abandoned PDF staging artifacts. Existing EPUB projects,
   accepted projects, model packages, and generated audio remain untouched.

No Room migration is required because accepted PDFs use the existing project,
chapter, narration-block, and source-path columns.

## Open Questions

None. The remaining work is implementation and qualification of the selected
artifact, not a decision that changes the specified behavior or architecture.
