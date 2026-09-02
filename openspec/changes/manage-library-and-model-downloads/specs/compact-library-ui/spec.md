## Purpose

Keeps the primary library and book screens compact and usable by showing document
metadata and audiobook state without rendering complete imported document text.

## ADDED Requirements

### Requirement: Compact library and book views

The library and book detail screens SHALL show title, author, document type,
chapter status, generation progress, storage, playback controls, and available
actions without rendering the complete imported PDF/EPUB text.

#### Scenario: Large imported book is displayed

- **WHEN** a book contains many chapters or a large amount of text
- **THEN** the main library and detail screens remain compact and do not render all narration text

### Requirement: Explicit text preview

The application MAY provide full or sampled text only behind an explicit preview
or details action. Hiding text from the main screens SHALL NOT remove the text
from the imported document or generation pipeline.

#### Scenario: User requests a preview

- **WHEN** the user explicitly opens document text preview
- **THEN** the app shows the requested preview without changing imported content or generation state
