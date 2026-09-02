## Purpose

Provides dependable local PDF selection and import on supported Android devices,
with safe staging, useful failure categories, and no dependency on remote content.

## ADDED Requirements

### Requirement: Local PDF selection and staging

The application SHALL accept a readable local PDF selected through the Android
document picker, copy its bytes once into private app storage, and perform all
inspection from that staged copy. Provider disappearance after staging SHALL NOT
prevent inspection, and failed or canceled staging SHALL leave no temporary file.

#### Scenario: Local PDF is selected

- **WHEN** the user selects a readable PDF from device storage
- **THEN** the app stages it privately, reports its page count, and shows a text preview before acceptance

#### Scenario: Provider cannot be opened

- **WHEN** the selected URI cannot be opened or copied
- **THEN** the app shows an actionable local-file/provider diagnostic and does not publish an import

### Requirement: Safe PDF inspection and acceptance

The application SHALL distinguish malformed, protected, unsupported, image-only,
empty, limit, timeout, and source-access failures without exposing paths, URIs,
source text, or raw exceptions. Accepted pages SHALL retain their order and
provenance and SHALL enter the existing document model without starting audio
generation.

#### Scenario: Readable text PDF is accepted

- **WHEN** the selected page range contains extractable text and no blocking diagnostic
- **THEN** the user can accept the preview and the project, chapters, blocks, and private source are persisted atomically

#### Scenario: Image-only or protected PDF is selected

- **WHEN** inspection identifies an image-only or protected document
- **THEN** acceptance is blocked with a category-specific recovery message and no partial text or project is published

### Requirement: Real-device PDF regression coverage

The PDF picker, staging, preview, cancellation, diagnostics, and acceptance path
SHALL be exercised on the API 33 ARM64 production device with at least one
real locally stored text PDF and one failure case.

#### Scenario: Device regression passes

- **WHEN** the supported API 33 device runs the local PDF regression suite
- **THEN** evidence records the selected file class, result, cleanup behavior, and any remaining provider limitation
