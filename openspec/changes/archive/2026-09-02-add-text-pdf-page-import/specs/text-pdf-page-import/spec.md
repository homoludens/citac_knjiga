## Purpose

Allow users to safely preview and import one bounded page range from a local,
born-digital PDF into the existing offline audiobook document pipeline.

## ADDED Requirements

### Requirement: SAF PDF selection and bounded inspection

The application SHALL let the user select a local PDF through the Android
Storage Access Framework, copy it to private temporary storage, and calculate
its content fingerprint before inspection. It MUST use this private copy for
all later inspection and MUST obtain and display the total 1-based page count
before accepting a range. The acceptance profile is exact: source file at
most 512 MiB, at most 10,000 pages, one inclusive range of at most 200 pages,
at most 1 MiB of extracted UTF-8 text per page, at most 32 MiB of extracted
UTF-8 text for the range, and at most 120 seconds of wall-clock inspection and
preview processing. MiB means 1,048,576 bytes. Any limit exceeded MUST fail
the inspection without publication.

#### Scenario: Valid SAF selection reports page count

- **WHEN** the user selects a readable PDF through SAF whose file, page count, and inspection work are within the profile
- **THEN** the app privately stages the source, records its fingerprint, and shows its total page count before range acceptance

#### Scenario: First exceeded limit blocks inspection

- **WHEN** the source is 512 MiB plus one byte, has 10,001 pages, selects 201 pages, exceeds either text limit, or exceeds 120 seconds
- **THEN** inspection stops with the matching bounded-limit diagnostic and no project or accepted document is published

### Requirement: Single contiguous page range

The user MUST provide exactly one contiguous inclusive range using 1-based page
numbers, with `1 <= start <= end <= pageCount`. The application MUST reject
disjoint ranges, zero-based or out-of-bounds pages, reversed bounds, and an
empty range.

#### Scenario: Inclusive range is accepted

- **WHEN** a 20-page PDF has the selected range `3-5`
- **THEN** pages 3, 4, and 5, in that order, are the only pages inspected for import

#### Scenario: Disjoint or invalid range is rejected

- **WHEN** the user enters `3,5`, `0-2`, `6-4`, or `19-21` for a 20-page PDF
- **THEN** the app reports range validation failure and does not extract or publish a project

### Requirement: Preview before acceptance

The application SHALL present extracted text for every selected page, in page
and reading order, together with each page's 1-based provenance locator and
all extraction warnings or blocking diagnostics before enabling acceptance.
The preview MUST be based on the staged source and MUST NOT create a project,
chapter, narration block, or generation job.

#### Scenario: Preview exposes text and provenance

- **WHEN** pages 3-5 produce readable text
- **THEN** the preview shows the extracted text for pages 3, 4, and 5 with locators identifying pages 3, 4, and 5 before the user can accept

#### Scenario: Preview remains unpublished

- **WHEN** the user is reviewing a valid PDF preview and has not accepted it
- **THEN** no project, chapter, narration block, or audiobook-generation job exists for that import

### Requirement: Existing ordered document projection

On acceptance, the application SHALL convert the selected pages into the
existing ordered project, chapter, and narration-block representation. Each
selected page with narratable text MUST contribute one ordered chapter whose
narration blocks retain the page's reading order; page order MUST be preserved
and no PDF-specific audiobook or generation pipeline may be required. The
source fingerprint and a stable 1-based page locator MUST be retained on the
project and every resulting chapter or narration block.

#### Scenario: Accepted pages use the existing pipeline

- **WHEN** the user accepts a preview for pages 3-5 containing narratable text
- **THEN** the app creates the existing project/chapter/narration-block records in page order 3, 4, 5, with the existing downstream generation flow available

#### Scenario: Provenance survives acceptance

- **WHEN** the accepted project is reopened after the original SAF URI is unavailable
- **THEN** the project remains identified by the recorded source fingerprint and each imported chapter/block still identifies its source page

### Requirement: Empty and image-only page rejection

The importer MUST detect a selected page with no extractable text or with only
an image/scanned representation and MUST block acceptance with an actionable
`OCR_UNSUPPORTED` diagnostic. It MUST NOT silently create empty narration
content or claim that OCR was performed.

#### Scenario: Image-only page is blocked

- **WHEN** any page in the selected range contains only a scanned image and no extractable text
- **THEN** preview marks that page as image-only, reports OCR unsupported, and acceptance remains blocked

#### Scenario: Empty page is blocked

- **WHEN** any selected page has no extractable text and no narratable content
- **THEN** the importer reports the empty-page blocking diagnostic and creates no project

### Requirement: Deterministic reading-order diagnostics

The importer SHALL apply a deterministic layout profile to classify reading
order. A clearly separated multi-column page with non-interleaving columns MAY
be accepted only with a visible warning and page provenance. A page whose
columns overlap or whose text blocks cannot be assigned a deterministic order
MUST block acceptance with an unreliable-layout diagnostic. The importer MUST
not silently present uncertain order as reliable.

#### Scenario: Separated multi-column page warns

- **WHEN** a selected page has two clearly separated columns whose blocks are ordered consistently within each column
- **THEN** preview shows a multi-column reading-order warning and permits acceptance only after the warning is visible

#### Scenario: Interleaved layout blocks

- **WHEN** text columns overlap or their blocks have no deterministic reading order
- **THEN** preview reports an unreliable-layout blocking diagnostic and acceptance is disabled

### Requirement: Local-only resource handling

PDF inspection and text extraction MUST read only the privately staged bytes of
the selected source. The application MUST NOT fetch, resolve, execute, or
follow network, remote-file, embedded external, annotation, hyperlink, or
other external URI resources during selection, inspection, preview, acceptance,
or downstream narration preparation.

#### Scenario: External PDF reference is encountered

- **WHEN** a PDF contains a remote hyperlink, external file action, or external resource reference
- **THEN** the reference is ignored or reported without opening it, and extraction continues only from the selected private PDF bytes

### Requirement: Safe failure for unsupported PDFs

Malformed, truncated, structurally unsupported, encrypted, password-protected,
or otherwise unreadable PDFs MUST fail with a user-visible diagnostic and MUST
not be treated as partially valid text imports. Failure handling MUST leave
existing projects unchanged.

#### Scenario: Password-protected PDF fails closed

- **WHEN** the selected PDF requires a password or cannot be decrypted
- **THEN** the app reports that protected PDFs are unsupported and creates no preview project or accepted project

#### Scenario: Malformed PDF fails safely

- **WHEN** PDF structure is malformed or the format is unsupported
- **THEN** inspection stops with a safe failure diagnostic and no extracted text or project is published

### Requirement: Cancellation and failure cleanup

Cancellation at selection, inspection, preview, or acceptance failure MUST
remove the import's temporary source, extracted text, diagnostics, and other
temporary state. Acceptance MUST be atomic: a failure MUST roll back any
partial publication and MUST NOT leave a new project, chapter, narration block,
or generation job.

#### Scenario: User cancels preview

- **WHEN** the user cancels after SAF staging but before acceptance
- **THEN** all temporary import state is removed and no project is created

#### Scenario: Acceptance publication fails

- **WHEN** a write, fingerprint, or indexing operation fails while accepting the PDF
- **THEN** the app reports failure, removes partial PDF-import state, preserves existing projects, and creates no partial new project

### Requirement: Existing generation ownership

Accepted PDF narration MUST enter the same ordered narration-block and durable
generation flow used by existing document imports. The PDF importer MUST NOT
start, define, or depend on a parallel audiobook, TTS, playback, or export
pipeline; generation remains an explicit downstream operation after import.

#### Scenario: Import does not start generation

- **WHEN** the user accepts a valid PDF page-range preview
- **THEN** the app creates only the existing document/project records and leaves audiobook generation pending until the existing generation action is invoked
