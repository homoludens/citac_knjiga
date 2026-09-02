## Purpose

Demonstrates that generated chapter audio can leave the application as a valid,
ordered audiobook artifact that independent Android players can consume.

## ADDED Requirements

### Requirement: Portable chapter export

The application SHALL export selected ready chapters as ordered, correctly named,
metadata-bearing audio files with a manifest, and the resulting files SHALL play
correctly in at least two independent external Android audio players.

#### Scenario: Exported chapter is portable

- **WHEN** a ready chapter is exported to a writable user-selected destination
- **THEN** the output is verified by the app and plays from start to finish in two external Android players with correct order and duration

#### Scenario: Export cannot be verified

- **WHEN** an encoder, destination, or external-player check fails
- **THEN** the app reports the failure, preserves the internal verified audio, and does not claim portable export success

### Requirement: Recoverable export evidence

Export verification SHALL record the artifact checksum, duration, codec/container,
player identities and versions, destination outcome, and any limitations without
including private document text or credentials.

#### Scenario: Export is interrupted

- **WHEN** export or external verification is interrupted
- **THEN** already verified internal artifacts remain usable and the export can be retried without silently duplicating or corrupting chapters
