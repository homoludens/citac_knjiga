## Context

See `proposal.md` and `specs/text-pdf-page-import/spec.md` for the motivation and
acceptance behavior. The original PDF section in `citac_knjiga.md` describes PDF
as layout-oriented best-effort input and keeps OCR out of the MVP.

The current app puts the EPUB picker, preview, and acceptance flow in
`app/.../CitacKnjigaApp.kt`, wires services manually in `AppContainer`, and runs
preview work on an IO coroutine. `document-epub` already stages SAF bytes,
fingerprints them, previews before publication, renders canonical text, and
uses `AtomicArtifactStore` for private artifacts. Its source URI is provenance;
later parsing uses the private source path.

The persistent core already stores a project fingerprint and source path/URI,
and stores string source locators on both chapters and narration blocks. Room is
the state authority, while files are bulk artifacts. Generation state and model
provenance are owned by the existing generation services; import must not
create generation rows or invoke TTS.

## Goals / Non-Goals

**Goals:**

- Add a PDF-specific SAF staging and bounded page inspection boundary beside the
  existing EPUB flow, without using a URI after staging.
- Make the parser return an inspectable, format-neutral document shape that can
  be projected into the current chapters, narration blocks, Markdown, and
  downstream generation flow.
- Make reading-order decisions deterministic and conservative, with visible
  warnings and fail-closed diagnostics for uncertainty.
- Make accepted publication atomic at both the file boundary and the Room
  projection boundary, with owner-scoped cleanup on every terminal path.
- Qualify a parser on Android and against the project's dependency and
  licensing rules before adding any production dependency.

**Non-Goals:**

- OCR, image recognition, PDF rendering for user editing, PDF mutation, or
  arbitrary layout reconstruction.
- Multiple disjoint ranges, user editing of extracted text, or a second
  audiobook/TTS/playback/export pipeline.
- Header/footer removal or dehyphenation that silently removes or changes source
  content. Such content is retained unless a later explicitly specified rule
  says otherwise.

## Decisions

**SAF staging and ownership.** Add a PDF source reader abstraction equivalent to
`EpubSourceReader` and a `SafPdfSourceRepository`. `OpenDocument` is filtered to
`application/pdf`, but the filter is advisory and validation remains
authoritative. The repository copies the selected stream to
`temporary/pdf-<projectId>/source.pdf`, calculating SHA-256 and counting bytes
in the same pass. The staged value contains the generated project ID, original
URI string, private file, size, and lowercase fingerprint. All PDF inspection,
preview, and acceptance reads use that exact private file and recheck its
canonical path, size, and fingerprint.

The owner ID is the only authority for temporary cleanup. Source bytes,
extracted page text, preview diagnostics, and any temporary canonical output are
under the owner directory. Cancellation, duplicate detection, parser failure,
timeout, range failure, acceptance failure, and explicit discard delete that
owner's files in `finally`; cleanup must not follow a source-provided filename
or URI. The staged source is retained only while its preview is live, then is
deleted after successful publication or terminal failure. Existing stale
temporary cleanup remains the process-death fallback.

The durable PDF source path is `sources/<projectId>/source.pdf`; add a
format-specific storage accessor rather than changing the existing EPUB path.
The original SAF URI is stored as provenance only and is never reopened.

**Picker and preview integration.** Keep the PDF UI in `StartScreen`, next to
the existing EPUB import section, rather than adding a navigation route or
putting import controls in `BookRoute`. `AppContainer` and `MainActivity` pass a
PDF preview service alongside the EPUB service. The PDF callback first stages
the source and reads the total 1-based page count. A separate PDF UI state then
shows the count and two 1-based range fields. It accepts only `1 <= start <= end
<= pageCount` and at most 200 pages; invalid, empty, reversed, zero-based, or
disjoint input never starts extraction.

After range validation, the service inspects only that inclusive range and
returns a state containing every page's ordered text, blocks, page locator,
warnings, and blocking diagnostics. The UI displays each page separately,
shows warnings before acceptance, and disables acceptance for any blocking
diagnostic. The loading job is cancellable and cancellation calls the service's
discard path. PDF acceptance does not call `EpubChapterProofService`, create a
generation job, or expose its one-shot generation controls; the existing
library/book generation action remains the downstream owner.

**Importer contract and shared IR.** Put a small format-neutral IR in `core`
so both document importers can depend on it without making `core` depend on a
format module. Existing EPUB types can be adapted incrementally; PDF must not
duplicate the Room projection. The essential contract is:

```kotlin
interface PdfPageImporter {
    fun pageCount(source: StagedPdfSource): PdfPageCountResult
    fun inspect(source: StagedPdfSource, range: PageRange): PdfImportInspection
}

data class PdfImportInspection(
    val pageCount: Int,
    val pages: List<PdfPage>,
    val warnings: List<ImportWarning>,
    val blockingDiagnostics: List<ImportDiagnostic>,
    val provenance: ImportProvenance,
)

data class PdfPage(
    val pageNumber: Int,
    val text: String,
    val blocks: List<DocumentBlock>,
    val locator: PageLocator,
)
```

`DocumentBlock` contains the existing narration block type, ordinal, source
text, and a `SourceLocator`; `DocumentChapter` and `DocumentIr` contain ordered
chapters, metadata, blocks, and source provenance. `PageLocator` is an opaque
value with the fingerprint and 1-based page number, rendered canonically as
`pdf:sha256=<lowercase-64-hex>/page/<number>`; block locators append a stable
`/block/<ordinal>`. These locators are not URI targets and must never be
resolved.

On acceptance, each selected page with narratable text becomes one ordered
chapter, titled with a safe PDF page fallback such as `Page <number>` unless
qualified document metadata supplies a better title. Its blocks retain page
order and block order. The shared renderer emits deterministic UTF-8 Markdown
with the same locators, while the IR and database retain the structured blocks.

**Parser qualification before dependency selection.** Build a disposable,
isolated qualification consumer like `readium-spike`, not a production
dependency, and compare AndroidX PDF, PDFBox-Android, and any viable existing
or platform option. `PdfRenderer` or another platform API is a candidate only
if it actually supplies the required text and block geometry; no capability is
assumed from its name.

The spike must run the same fixtures and acceptance checks on API 30, API 35,
and API 36. It records, for each candidate:

- page count, Serbian Latin/Cyrillic and Unicode fidelity, line/paragraph
  handling, page boundaries, bounding boxes, one-column order, separated
  multi-column order, and ambiguous-layout behavior;
- encrypted/password-protected, malformed, truncated, unsupported, and
  partially readable PDFs, including whether the candidate fails closed without
  publishing partial text;
- behavior for embedded files, hyperlinks, file actions, and external
  references, proving that no candidate opens or resolves them;
- source closure and build reproducibility, license compatibility and notice
  obligations, transitive dependencies, APK size delta for the release arm64
  variant, memory/time behavior under the import profile, API stability, and
  upstream maintenance;
- integration cost with the current Java 17/Kotlin/Android module setup,
  compile/target SDK conventions, F-Droid source-build constraints, dependency
  locking, verification metadata, and offline builds.

The report has a binary qualification result and preserves fixture outputs and
measurements. No parser coordinate, version-catalog alias, Gradle dependency,
lockfile entry, verification checksum, or notice is described as selected until
one candidate passes every required gate. If none passes, PDF import remains
unavailable rather than silently falling back to an unqualified parser.

**Deterministic extraction and normalization.** The qualified adapter must
expose text spans or blocks with page coordinates. Normalize coordinates to the
page rectangle and use a fixed, versioned layout profile:

1. Find at most one usable vertical gutter. A gutter is a gap of at least 8% of
   page width that no block crosses and that leaves blocks on both sides. The
   largest gap wins; ties choose the leftmost gap. No gutter means one-column
   ordering. More than two columns, a block crossing the gutter, or multiple
   incompatible candidates is unreliable rather than guessed.
2. Order blocks within each column from top to bottom. Blocks that occupy the
   same row are ordered left to right only when their horizontal rectangles do
   not overlap. A block with no provable relation to its neighbor is a blocking
   `UNRELIABLE_LAYOUT` diagnostic. The parser's input order is only a final tie
   breaker for geometrically identical blocks, never a replacement for missing
   geometry.
3. A detected two-column order produces a visible `MULTI_COLUMN` warning. A
   one-column page with ambiguous overlaps blocks acceptance. Warning and
   diagnostic ordering is page order followed by block order.

Normalization changes line endings to LF, converts layout-only whitespace to
   explicit spaces or paragraph breaks, and trims only boundary whitespace.
   Joining a soft line break never removes a non-whitespace character;
   discretionary hyphens, repeated headers, footers, page numbers, and other
   repeated text remain in the preview and narration. If a later safe detector
   identifies such content, it may warn but must retain it in the returned text
   unless a separate requirement defines its removal. UTF-8 byte counts are
   taken from the exact normalized text returned by the importer.

**Image-only detection and local resources.** After normalization, a page with
no extractable text and an image/scanned content marker is marked image-only
and receives blocking `OCR_UNSUPPORTED`. A page with no extractable or
narratable text and no image marker receives the empty-page diagnostic. If the
candidate cannot distinguish those cases, it fails closed as unsupported
instead of claiming OCR or creating an empty chapter. No OCR code, model, or
confidence value is introduced.

The adapter receives only the staged file and a non-networking resource policy.
It may read PDF objects needed for selected page text and page geometry, but it
must ignore or report hyperlinks, embedded-file actions, annotations, remote
references, and external file specifications without opening them. No
`ContentResolver` call, network client, URL resolver, or filesystem path derived
from PDF content is available below the parser boundary.

**Fixed bounds and timeout.** Use one immutable production profile; do not make
these values user-configurable. Every comparison accepts equality and fails on
the first observed value greater than the limit:

| Counter | Inclusive maximum |
|---|---:|
| staged source bytes | 536,870,912 bytes (512 MiB) |
| total page count | 10,000 pages |
| selected pages | 200 pages |
| normalized extracted UTF-8 bytes per page | 1,048,576 bytes (1 MiB) |
| normalized extracted UTF-8 bytes for the range | 33,554,432 bytes (32 MiB) |
| inspection and preview wall-clock time | 120 seconds |

The copy counter stops on byte 536,870,913 and removes the partial source.
Page-count validation precedes range validation; range extraction never loads
unselected page text. Per-page and range counters increment from encoded UTF-8
bytes, not UTF-16 character counts. A monotonic `System.nanoTime()` deadline is
created at page-count inspection and is checked before and after each parser,
page, normalization, and preview operation. The same deadline is passed into
the parser adapter, and coroutine cancellation is terminal. A candidate that
cannot honor cancellation/deadline checks cannot qualify. A timeout reports a
stable limit diagnostic and publishes nothing.

**Atomic acceptance and provenance.** Preview writes only owner-scoped
temporary state. Acceptance first verifies that the staged source still belongs
to the preview and that its fingerprint, size, selected range, and parser
result match. It then renders and validates source, per-page canonical text,
and the warning report through `AtomicArtifactStore`, using new project-ID
paths. Only after all files are ready does a Room transaction insert the one
project, its ordered chapters, and its narration blocks. A transaction failure
deletes only those new candidate files and leaves existing projects unchanged.

The project stores the fingerprint in `BookProjectEntity.sourceFingerprint`,
the private PDF path in `sourcePath`, and the original URI in `sourceUri`.
Every PDF chapter and block stores a locator containing the same fingerprint
and its stable 1-based page number. The existing columns are sufficient:
`source_locator` is already present on both rows, so no Room migration is
required. Do not add a parallel PDF table or generation provenance fields.
If a future requirement needs a separately queryable fingerprint column on
chapters or blocks, that is a new schema change and is outside this design.

The Room adapter must use one database transaction for the complete document
projection, unlike a sequence of independently visible inserts. It creates no
`GenerationRunEntity`, `AudioSegmentEntity`, or audio file. Chapter and block
statuses remain pending for the existing generation owner. Startup
reconciliation must treat unreferenced, aged candidate source/canonical files
as orphans using the existing `cleanupOrphanFiles` boundary, while protecting
paths referenced by Room and never deleting an existing project source.

**Fixtures and verification.** Add small redistributable PDFs for valid
one-column Latin/Cyrillic text, soft line wrapping, separated two-column text,
overlapping columns, repeated page decoration, empty pages, image-only pages,
encrypted/password-protected files, malformed/truncated structure, external
references, and unsupported encodings. Generate boundary cases rather than
checking in multi-gigabyte files for the exact byte counters.

JVM tests cover the range validator, UTF-8 counters, deadline behavior,
normalization preservation, layout ordering, diagnostics, IR projection, and
atomic rollback with fake readers and stores. Android instrumentation covers
the API 30/35/36 qualification matrix, SAF staging followed by source-provider
disappearance, private-path enforcement, parser cancellation, process-death
cleanup/reconciliation, Room transaction failure isolation, and the Compose
picker/range/preview/acceptance states. Tests assert that no external URI is
resolved and that a rejected or cancelled import leaves no project, chapter,
block, canonical text, diagnostic report, or generation job.

## Risks / Trade-offs

- [PDF text extraction and geometry differ across Android versions] -> Keep the
  adapter behind the qualified interface, run the same fixtures on API 30, 35,
  and 36, and fail closed when geometry cannot prove order.
- [A parser can allocate or block outside the app's counters] -> Require
  selected-page access, bounded UTF-8 counters, monotonic deadline checks,
  cancellation support, and qualification evidence before production use.
- [Conservative layout rules reject readable PDFs] -> Prefer a visible warning
  only for a provably separated two-column page; reject uncertain order rather
  than narrate silently reordered content.
- [Retaining headers, footers, and hyphens can produce less polished narration]
  -> Preserve all content and expose it in preview; cleanup can be a separately
  specified, reviewable transformation.
- [Publishing files and Room rows cannot share a filesystem transaction] ->
  Publish only new project-ID artifacts, commit one Room projection transaction,
  delete candidates on ordinary failure, and reconcile aged unreferenced files
  after process death.
- [A new parser may increase APK size or weaken F-Droid reproducibility] ->
  Measure release APK impact, inspect the complete source/license closure, and
  keep the unqualified spike out of production dependencies.
- [Temporary source files may contain private documents] -> Keep all owners
  below app-private storage, avoid source text in logs, delete on every terminal
  path, and retain only the accepted private source and required canonical
  artifacts.

## Migration Plan

1. Run the isolated parser qualification spike and record its API, fidelity,
   failure, safety, license/source-closure, size, maintenance, and build
   results. Until it passes, ship no PDF parser dependency or PDF import UI.
2. Add the shared IR, PDF staging/service boundary, fixed profile, preview UI,
   transactional document projection, storage accessor, diagnostics, and
   fixtures using the existing manual composition, version catalog, dependency
   locking, verification, and notice conventions.
3. If and only if qualification passes, pin the selected coordinates and
   checksums in the normal Gradle files and regenerate the relevant lock,
   verification, and third-party notice records. If no candidate passes, keep
   the feature disabled and retain the spike as evidence.
4. No Room migration is required. Existing EPUB rows, source paths, canonical
   files, and generation provenance remain unchanged; PDF provenance fits the
   existing project fingerprint and chapter/block locator columns.
5. Rollback is an application-code rollback: disable the PDF picker/service,
   retain existing accepted projects, and delete only abandoned PDF staging or
   unreferenced candidate files. No persisted data conversion is needed.

## Open Questions

- Which exact parser and version, if any, passes the qualification gate; this
  can be answered by the isolated spike without changing the IR, persistence,
  safety limits, or user flow.
