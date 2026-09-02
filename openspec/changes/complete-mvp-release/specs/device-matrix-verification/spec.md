## Purpose

Establishes the final sustained generation and playback evidence across Android
versions and the target vendor configuration before release-candidate claims are made.

## ADDED Requirements

### Requirement: Required Android device matrix

The project SHALL run sustained generation-plus-playback validation on Android
11, a current supported Android release, Android 16, and the Poco F3 vendor
battery-management configuration, or explicitly record an unavailable target as
an unresolved deviation.

#### Scenario: Matrix target is available

- **WHEN** a required Android target is tested
- **THEN** the evidence records generation, playback, battery, thermal, memory, interruption, and recovery observations for that target

#### Scenario: Matrix target is unavailable

- **WHEN** a required target cannot be executed in the available environment
- **THEN** the release report marks it unresolved and does not present the matrix as complete

### Requirement: Capability and deviation audit

Every capability scenario in the active specifications SHALL be mapped to a
passing test/evidence item or a named unresolved deviation before the MVP is
called a release candidate.

#### Scenario: Release-candidate audit is complete

- **WHEN** all capability scenarios have been reviewed
- **THEN** the audit contains traceable pass/fail/deferred results and no silent deviations
