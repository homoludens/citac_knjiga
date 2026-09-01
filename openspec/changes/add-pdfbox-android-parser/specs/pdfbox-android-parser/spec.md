## Purpose

Provide a local, bounded PDF extraction capability that turns selected pages into structured text for the existing document and audiobook pipeline without accepting unsafe or ambiguous content.

## ADDED Requirements

### Requirement: Structured page extraction

The PDF extraction capability SHALL inspect a privately staged PDF and return its total 1-based page count before range extraction. For a valid inclusive page range, it SHALL return only those pages, and each returned page SHALL include normalized UTF-8 text blocks, normalized page geometry, a stable 1-based page locator, and enough metadata to distinguish text, empty, image-only, and externally referenced content.

#### Scenario: Text page produces structured output

- **WHEN** a privately staged born-digital PDF contains readable text on selected pages
- **THEN** extraction returns those pages in page order with text blocks, normalized coordinates, and locators tied to the source fingerprint and page number

#### Scenario: Unselected pages are excluded

- **WHEN** the caller requests pages 3 through 5 from a larger PDF
- **THEN** the extraction result contains text and blocks only for pages 3, 4, and 5, with no page 1, 2, or 6 content

### Requirement: Geometry and text fidelity

The capability SHALL preserve the text and positional information needed to determine reading order outside parser content-stream order. It MUST preserve Serbian Latin, Serbian Cyrillic, and Unicode text, and MUST expose overlapping or separated text positions rather than silently presenting an uncertain order as reliable.

#### Scenario: Serbian text is preserved

- **WHEN** a selected page contains Serbian Latin, Serbian Cyrillic, and Unicode characters
- **THEN** the returned UTF-8 text contains the same readable characters after normalization without lossy transliteration

#### Scenario: Layout evidence is retained

- **WHEN** selected text is arranged in separated columns or overlapping regions
- **THEN** the result retains the positions needed for deterministic layout classification and does not discard or silently reorder the evidence

### Requirement: Bounded and cancellable inspection

The capability SHALL honor the import profile of at most 10,000 pages, 200 selected pages, 1 MiB of normalized UTF-8 text per page, 32 MiB of normalized UTF-8 text for the selected range, and 120 seconds of inspection and preview processing. It MUST check cancellation and the monotonic deadline during page-count and selected-page extraction, stop at the first exceeded limit, and publish no partial result.

#### Scenario: Extraction limit is exceeded

- **WHEN** page extraction first exceeds a configured production limit
- **THEN** extraction stops with the matching bounded-limit diagnostic and returns no accepted document or partial publication

#### Scenario: Caller cancels extraction

- **WHEN** cancellation is requested during page-count or selected-page extraction
- **THEN** extraction terminates, reports cancellation through the import boundary, and leaves no temporary parser output

### Requirement: Safe local failure

The capability SHALL fail closed for malformed, truncated, unsupported, encrypted, password-protected, unreadable, or image-only PDFs. It MUST report actionable diagnostics, MUST NOT claim OCR, and MUST read only the staged PDF bytes; it MUST not open network, remote-file, hyperlink, annotation, embedded-file, or other external resources referenced by the PDF.

#### Scenario: Protected or malformed PDF is rejected

- **WHEN** the selected PDF cannot be safely opened because it is protected or structurally invalid
- **THEN** extraction returns a user-visible failure diagnostic and publishes no text, preview project, or accepted project

#### Scenario: Image-only page is rejected without OCR

- **WHEN** a selected page contains only scanned or image content with no extractable text
- **THEN** extraction reports that OCR is unsupported and does not create an empty narration chapter

#### Scenario: External reference is ignored

- **WHEN** the PDF contains an external hyperlink, file action, embedded reference, or remote resource
- **THEN** extraction ignores or reports the reference without opening it, and continues only with bytes from the staged PDF
