## Purpose

Defines persistent, incremental audiobook generation that survives interruption, exposes accurate progress and errors, and regenerates only invalidated audio.

## ADDED Requirements

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

