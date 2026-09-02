## Purpose

Defines deterministic conversion of Serbian narration text into model-compatible phonemes and tokens while preserving safe linguistic boundaries and reproducible cache identity.

## ADDED Requirements

### Requirement: Reference-equivalent preprocessing
The system MUST reproduce the pinned desktop cleanup, normalization, Serbian phonemization, tokenization, punctuation handling, and boundary-token behavior exactly or through an explicitly documented equivalent.

#### Scenario: Golden vector validation
- **WHEN** the Android pipeline processes a published golden input
- **THEN** its normalized text, phoneme sequence, token IDs, and chunk boundaries match the expected vector

### Requirement: Serbian script support
The system SHALL support Serbian Latin and Cyrillic, including diacritics, Latin digraphs, mixed-script text, and script conversion rules used by the reference pipeline.

#### Scenario: Equivalent Latin and Cyrillic text
- **WHEN** semantically equivalent Latin and Cyrillic Serbian sentences are processed
- **THEN** they produce equivalent intended pronunciation subject only to documented script-specific exceptions

### Requirement: Narration normalization coverage
The system SHALL apply deterministic documented behavior to common Serbian numbers, ordinals, dates, times, decimals, currencies, percentages, measurements, Roman numerals, abbreviations, quotations, dashes, ellipses, URLs, email addresses, citations, and page artifacts.

#### Scenario: Unsupported or ambiguous construction
- **WHEN** an input cannot be normalized confidently
- **THEN** the system preserves a safe speakable representation or records a diagnostic instead of silently dropping the content

### Requirement: Linguistically safe chunking
The system SHALL split narration at chapter, paragraph, sentence, and clause boundaries while respecting the model's verified input limit and SHALL NOT knowingly split an abbreviation, decimal, protected token sequence, or grapheme.

#### Scenario: Oversized paragraph
- **WHEN** a paragraph exceeds the verified model limit
- **THEN** the system creates ordered chunks at the safest available boundaries and retains punctuation needed for prosody

### Requirement: Versioned processing output
Every processed block SHALL carry the preprocessing version, pronunciation-rule version, normalized text hash, phoneme hash, token hash, and source relationship required for selective invalidation.

#### Scenario: Processing rule update
- **WHEN** normalization or pronunciation rules change
- **THEN** only blocks whose effective processing output or version is affected are scheduled for regeneration

### Requirement: Inspectable test corpus
The project SHALL maintain machine-readable golden vectors covering Serbian Latin, Cyrillic, diacritics, digraphs, mixed scripts, foreign names, numbers, punctuation, abbreviations, and chunk-boundary edge cases.

#### Scenario: Continuous validation
- **WHEN** preprocessing code or resources change
- **THEN** automated tests compare all intermediate representations and report the first divergent stage

