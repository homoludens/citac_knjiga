## Purpose

Defines user-controlled export of a completed or partially completed project as portable, ordered chapter audio with metadata and recoverable progress.

## ADDED Requirements

### Requirement: MVP audio policy

The MVP SHALL use nominal 64,000 bps AAC-LC, 24 kHz mono, through a regular
Android `MediaCodec` encoder for lossy ready audio and portable chapter export.
The benchmark decision is provisional because task 10.1 used a synthetic fixture
on an API 35 x86_64 emulator; natural Serbian listening and Poco F3 ARM64
qualification remain pending.

Durable and playback artifacts SHALL be ordered narration segments. Chapters
SHALL group those segments for navigation, progress, and one-file-per-chapter
export. Segment boundaries SHALL follow the existing chapter/paragraph/
sentence/clause and model-safe chunk boundaries. The exporter SHALL preserve
chapter order and segment sequence.

The system SHALL NOT add silence only to compensate for AAC priming, padding, or
an encoder boundary. Explicit narration pauses are part of the PCM input. A
later boundary defect SHALL fail validation rather than be silently repaired.

If a compatible platform AAC-LC encoder is unavailable or fails, the system SHALL
preserve any existing verified ready artifact. A segment without such an artifact
MAY be published as validated private PCM16 WAV for in-app playback, but export
MUST report AAC unavailability instead of changing codec, bitrate, or file type.
Temporary raw PCM MAY be deleted only after a validated AAC or WAV artifact is
atomically published and its Room `READY` checkpoint records checksum, size,
duration, and provenance with no active retry/reference. Failed operations retain
the existing ready artifact and use normal stale-temporary reconciliation for
unreferenced abandoned files.

#### Scenario: AAC encoder is available
- **WHEN** a compatible regular platform AAC-LC encoder is available for a ready segment
- **THEN** the system requests 64,000 bps AAC-LC and preserves the segment's chapter and sequence ordering for later export

#### Scenario: AAC encoder is unavailable during export
- **WHEN** a compatible platform AAC-LC encoder is unavailable or fails
- **THEN** export records an actionable AAC failure without changing codec, bitrate, file type, or an existing verified ready artifact

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
