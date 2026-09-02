## Purpose

Defines safe lifecycle operations for imported PDF and EPUB books, including
permanent in-app deletion while protecting the user-owned original source file.

## ADDED Requirements

### Requirement: Delete an imported book

The application SHALL let the user delete an imported PDF or EPUB project from
the library after explicit confirmation. Deletion SHALL remove the app-owned
source copy, canonical text, cover, generated audio, generation records,
playback position, errors, and temporary artifacts associated with that project.
It SHALL NOT delete or modify the original external PDF or EPUB selected through
Android storage.

#### Scenario: User confirms deletion

- **WHEN** the user confirms deletion of an imported book with no active generation
- **THEN** the book and all of its app-owned data disappear from the library and the external source remains unchanged

#### Scenario: User cancels deletion

- **WHEN** the user dismisses or cancels the confirmation
- **THEN** the project, audio, playback position, and library state remain unchanged

### Requirement: Delete safely during generation or playback

The application SHALL stop playback and cancel active generation work before
deleting a project. A canceled worker SHALL be unable to publish new audio for a
deleted project, and deletion SHALL leave no visible partial project state.

#### Scenario: Delete a generating book

- **WHEN** the user confirms deletion while generation is queued or running
- **THEN** generation is stopped, project-owned artifacts are removed, and the book is absent after restart

#### Scenario: Delete a playing book

- **WHEN** the user confirms deletion while the book is playing
- **THEN** playback stops, the book is removed from the active queue, and unrelated books remain playable
