## Purpose

Defines user-controlled export of a completed or partially completed project as portable, ordered chapter audio with metadata and recoverable progress.

## ADDED Requirements

### Requirement: Portable chapter export
The application SHALL export selected ready chapters as consistently encoded, zero-padded and ordered audio files playable outside the application.

#### Scenario: Complete book export
- **WHEN** every selected chapter is ready and the user chooses a writable destination
- **THEN** the destination receives ordered chapter audio representing the selected book

#### Scenario: Incomplete project export
- **WHEN** one or more selected chapters are not ready
- **THEN** the application identifies missing chapters and requires the user to limit the selection or finish generation before claiming a complete export

### Requirement: Exported metadata and cover
The exporter SHALL include available title, author, chapter title, track order, cover art, model attribution reference, and a machine-readable export manifest.

#### Scenario: Source metadata is absent
- **WHEN** optional publication metadata or cover art is unavailable
- **THEN** export succeeds using safe fallback names while the manifest records the missing fields

### Requirement: User-selected destination
Export SHALL use an Android system-selected destination and SHALL NOT overwrite an existing audiobook without explicit confirmation.

#### Scenario: Name collision
- **WHEN** a target filename already exists
- **THEN** the application asks for confirmation, chooses a non-conflicting name, or cancels without modifying the existing file

### Requirement: Recoverable and atomic export
The system SHALL persist export progress, write each file through a temporary or provider-safe strategy, verify completed output, and allow retry after interruption.

#### Scenario: Export destination becomes unavailable
- **WHEN** access is lost during export
- **THEN** already verified files remain identifiable, the export records where it stopped, and retry does not duplicate or silently corrupt chapters

### Requirement: Export storage validation
The application SHALL estimate target and temporary storage with a safety margin before export and surface provider write failures clearly.

#### Scenario: Insufficient destination capacity
- **WHEN** the selected destination cannot hold the estimated export
- **THEN** export stops before claiming success and preserves the internal audiobook project

