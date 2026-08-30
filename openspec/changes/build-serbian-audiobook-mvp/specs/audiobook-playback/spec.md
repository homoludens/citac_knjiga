## Purpose

Defines reliable offline audiobook playback over incrementally generated segments and chapters, including standard media controls and persistent listening position.

## ADDED Requirements

### Requirement: Segment playback with chapter grouping
The player SHALL use verified ordered audio segments as its media items. Chapters
SHALL provide navigation and progress grouping over those items without replacing
segment-level generation, retry, invalidation, or readiness state.

#### Scenario: Partial chapter is available
- **WHEN** a chapter has verified ready segments and later segments are pending or failed
- **THEN** the player can play the ready segments in sequence, reports the chapter as partial, and does not synthesize missing audio or reorder segments

#### Scenario: AAC encoding is unavailable
- **WHEN** platform AAC-LC encoding is unavailable or fails for a segment
- **THEN** the player may play a validated private PCM16 WAV fallback, while preserving any existing ready artifact and reporting portable M4A export as unavailable

### Requirement: Progressive offline playback
The application SHALL play verified completed chapters or segments without network access while later content is pending or generating.

#### Scenario: Generation continues during playback
- **WHEN** the user starts a completed chapter while later chapters are generating
- **THEN** playback continues independently and newly completed content becomes available without corrupting the active queue

### Requirement: Audiobook navigation and controls
The player SHALL provide play, pause, seek, previous and next chapter, configurable jump backward and forward, chapter selection, and playback speed.

#### Scenario: Chapter navigation
- **WHEN** the user selects another completed chapter
- **THEN** playback moves to that chapter and the displayed book and chapter state remain synchronized

### Requirement: Persistent listening position
The application SHALL persist the current book, chapter or segment, position, and playback speed frequently enough to survive normal process termination and device reboot.

#### Scenario: Resume after restart
- **WHEN** the user reopens a previously played book after restarting the app or device
- **THEN** playback can resume from the last safely stored position

### Requirement: System media integration
The player SHALL expose a media notification, lock-screen and headset/Bluetooth controls, audio focus behavior, and interruption handling appropriate for audiobook playback.

#### Scenario: Incoming audio interruption
- **WHEN** another application temporarily gains audio focus
- **THEN** playback pauses or ducks according to platform policy and resumes only when appropriate

### Requirement: Missing or invalid audio handling
The player SHALL skip or stop safely at unavailable, stale, or corrupt audio and present a regeneration or retry route rather than failing the whole library.

#### Scenario: Next segment is unavailable
- **WHEN** playback reaches a segment that is not ready
- **THEN** the player stops at the last valid position or advances to valid content according to documented behavior and informs the user
