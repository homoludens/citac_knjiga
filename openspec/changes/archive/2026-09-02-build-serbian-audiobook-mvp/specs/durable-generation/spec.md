## Purpose

Defines persistent, incremental audiobook generation that survives interruption, exposes accurate progress and errors, and regenerates only invalidated audio.

## ADDED Requirements

### Requirement: Encoding fallback and raw-PCM lifecycle
The system SHALL use nominal 64,000 bps AAC-LC as the MVP lossy encoding policy
for 24 kHz mono segment artifacts. A missing or failed compatible platform
encoder SHALL not replace a verified ready artifact or silently select another
codec or bitrate. A segment without a ready artifact MAY fall back to a
validated private PCM16 WAV for in-app playback, but portable AAC export SHALL
remain failed and retryable.

Temporary raw PCM SHALL remain until the generated PCM and the selected AAC or
WAV artifact have passed validation, the artifact has been atomically published,
and the Room segment `READY` checkpoint has recorded its checksum, size, duration,
and provenance with no active retry/reference. Failed or interrupted work keeps
the current ready artifact unchanged; unreferenced abandoned temporary files are
handled by the existing stale-temporary reconciliation policy.

#### Scenario: Failed encode with an existing ready segment
- **WHEN** a replacement AAC encode fails during configuration, encoding, validation, or publication
- **THEN** the existing verified artifact and Room `READY` state remain unchanged, the failure is recorded, and temporary output is not published over it

#### Scenario: Successful PCM fallback
- **WHEN** no compatible AAC-LC encoder is available for a segment without a current ready artifact
- **THEN** validated PCM16 WAV is atomically published as the private playback artifact, Room provenance is recorded, and only then may its staging PCM be deleted

### Requirement: Persistent incremental generation
The system SHALL generate independently recoverable narration segments in deterministic order and persist job state outside process memory.

#### Scenario: Process terminates during a segment
- **WHEN** the application process dies during generation
- **THEN** already verified segments remain complete and the interrupted segment returns to a recoverable state without being mistaken for valid audio

### Requirement: Atomic audio publication
The system MUST write generated audio to a temporary location, validate it for finite samples, non-silence, clipping, plausible duration, format readability, and checksum, and only then publish it as ready.

#### Scenario: Invalid model output
- **WHEN** generation yields NaN values, silence, corrupt encoding, or implausible duration
- **THEN** the temporary artifact is rejected and the segment records an actionable failure

### Requirement: User job controls
The application SHALL support generate, pause, resume, cancel, and retry actions with a visible foreground notification while active work requires it.

#### Scenario: Pause requested
- **WHEN** the user pauses an active book
- **THEN** the current atomic segment finishes or stops safely and no new segment starts until resume is requested

### Requirement: Selective regeneration and caching
The system SHALL reuse ready segments whose generation key and file integrity still match and SHALL invalidate only segments affected by changed text, processing, model, voice, or inference settings.

#### Scenario: One narration block changes
- **WHEN** a single block obtains a new effective generation key
- **THEN** that block's audio is regenerated while unaffected ready segments are reused

### Requirement: Progress and failure visibility
The application SHALL show book and chapter progress based on segment work, identify failed segments, and preserve errors and retry counts across restarts.

#### Scenario: Mixed successful and failed segments
- **WHEN** a generation run finishes all runnable work with some failures
- **THEN** completed audio remains playable and the project presents the failed items and retry action

### Requirement: Storage safeguards
The system SHALL estimate required temporary, internal, and export storage before large work, recheck capacity during generation, and handle write failures without corrupting project state.

#### Scenario: Insufficient storage before generation
- **WHEN** available space is below the declared requirement and safety margin
- **THEN** generation does not start and the application explains the estimate and cleanup options

### Requirement: Restart reconciliation
The system SHALL reconcile database state, temporary files, ready audio, and pending work after app restart, device reboot, application update, or storage interruption.

#### Scenario: Device reboot
- **WHEN** a device reboots with unfinished generation work
- **THEN** the project remains resumable and does not regenerate verified segments unless the user resumes or configured constraints permit automatic continuation
