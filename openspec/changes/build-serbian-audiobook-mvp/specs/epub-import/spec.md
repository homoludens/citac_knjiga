## Purpose

Defines safe import of DRM-free EPUB 2 and EPUB 3 publications into persistent metadata, ordered chapters, inspectable Markdown, and structured narration blocks.

## ADDED Requirements

### Requirement: User-selected EPUB import
The application SHALL import a user-selected EPUB through the Android system document picker, copy it into app-private storage, calculate a content fingerprint, and retain source provenance.

#### Scenario: Source provider later disappears
- **WHEN** the original document URI becomes unavailable after a successful import
- **THEN** the project remains usable from its verified private source copy

### Requirement: Publication structure preservation
The importer SHALL follow the EPUB package spine rather than archive filename order and SHALL preserve title, author, language, cover, table of contents, chapter order, and heading hierarchy when supplied by the publication.

#### Scenario: Filenames disagree with spine order
- **WHEN** archive entry names sort differently from the declared spine
- **THEN** chapters appear in declared spine order

### Requirement: Structured narration representation
The importer SHALL produce canonical per-chapter Markdown and ordered typed narration blocks with stable source locators for headings, paragraphs, list items, quotations, poetry, captions, notes, scene breaks, and intentionally skipped content.

#### Scenario: Inspect imported chapter
- **WHEN** import completes
- **THEN** the user can preview chapter titles and cleaned narration while diagnostics retain a mapping to the source location

### Requirement: Deterministic narration cleanup
The importer SHALL remove scripts, styling, duplicate navigation, repeated headings, and declared non-narrative boilerplate without silently removing ordinary narrative text.

#### Scenario: Ambiguous content
- **WHEN** content cannot be classified safely as narrative or boilerplate
- **THEN** the importer keeps it or surfaces an import warning rather than dropping it without notice

### Requirement: Untrusted archive protection
The importer MUST reject archive path traversal, encrypted or DRM-protected content, dangerous XML constructs, excessive entry counts, excessive uncompressed size or compression ratios, and external resource loading beyond declared limits.

#### Scenario: Malicious archive entry
- **WHEN** an entry resolves outside the import sandbox or exceeds a configured safety limit
- **THEN** import stops safely, temporary output is cleaned up, and no file outside the project area is modified

### Requirement: Import diagnostics
The system SHALL report unsupported features, missing metadata, malformed navigation, empty chapters, skipped resources, and cleanup uncertainty without preventing use of valid recovered content.

#### Scenario: Partially malformed EPUB
- **WHEN** valid spine content can be recovered despite non-critical publication errors
- **THEN** the project is imported with actionable warnings attached

