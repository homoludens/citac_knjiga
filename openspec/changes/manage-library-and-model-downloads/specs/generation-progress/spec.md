## Purpose

Makes long-running document-to-audiobook conversion understandable through
approximate word-based progress, percentage progress, and distinct model-download progress.

## ADDED Requirements

### Requirement: Approximate generation progress

The application SHALL show generation progress for a chapter and book as an
approximate number of narratable words and a percentage. Progress SHALL update as
segments complete, survive process restart, and distinguish queued, running,
paused, failed, canceled, and completed states.

#### Scenario: Generation is running

- **WHEN** some but not all selected narration has generated
- **THEN** the library shows an approximate completed-word count, total-word count, and percentage that increases as work completes

#### Scenario: Generation is complete or unavailable

- **WHEN** generation completes, fails, or is canceled
- **THEN** the displayed progress and status accurately identify the terminal or recoverable state without claiming ungenerated words are ready

### Requirement: Download progress is separate

Model downloads SHALL show transferred bytes or percentage independently from
book-generation progress, including a verifying state after transfer completes.

#### Scenario: Model package is downloading

- **WHEN** a Kokoro or VITS release asset is being downloaded
- **THEN** the UI reports download progress and does not present the package as installed before verification finishes
