## Purpose

Provides user-controlled regeneration of audiobook audio for either one chapter
or an entire imported PDF/EPUB project using the currently selected voice engine.

## ADDED Requirements

### Requirement: Regenerate a chapter or book

The application SHALL expose regeneration actions for an individual chapter and
for the complete imported book. Both PDF and EPUB narration blocks SHALL use the
same durable generation behavior, and the selected Kokoro or VITS engine SHALL
be used for newly generated audio.

#### Scenario: Regenerate one chapter

- **WHEN** the user chooses regeneration for a chapter
- **THEN** only that chapter's audio is removed and requeued while other chapters retain their current state

#### Scenario: Regenerate an entire book

- **WHEN** the user chooses regeneration for the complete book
- **THEN** audio for every narratable chapter is removed and the complete book is requeued

### Requirement: Replace audio fail-closed

Regeneration SHALL remove the selected existing audio before new generation
starts, and the selected scope SHALL remain without playable generated audio until
replacement segments have been validated and published. A failed regeneration
MUST NOT corrupt source text, other chapters, or unrelated projects.

#### Scenario: Regeneration succeeds

- **WHEN** all selected segments generate and validate successfully
- **THEN** the selected chapter or book becomes playable from the new audio with updated engine provenance

#### Scenario: Regeneration fails

- **WHEN** one or more selected segments fail, are canceled, or lose storage
- **THEN** the selected scope reports an actionable failure and no invalid or partial segment is marked ready
