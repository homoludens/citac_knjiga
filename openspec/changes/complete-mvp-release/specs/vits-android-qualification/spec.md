## Purpose

Completes qualification of the optional Serbian VITS backend on the production
Android target while preserving Kokoro as the safe default and preserving offline behavior.

## ADDED Requirements

### Requirement: API 33 ARM64 VITS qualification

The VITS backend SHALL pass an offline API 33 `arm64-v8a` qualification run on
the production device and an equivalent API 33 target before the VITS feature is
treated as release-qualified. Evidence SHALL cover output correctness, timing,
memory, interruption, recovery, and absence of network access.

#### Scenario: VITS qualification passes

- **WHEN** the validated VITS package generates the required Serbian fixtures offline on both API 33 targets
- **THEN** the report records passing correctness and operational evidence and the feature gate may remain enabled

#### Scenario: VITS qualification is incomplete or fails

- **WHEN** any required parity, interruption, recovery, or no-network evidence is missing or fails
- **THEN** VITS remains explicitly experimental and Kokoro remains the default release-safe engine

### Requirement: Stable VITS audio and provenance

Qualified VITS generation SHALL publish only validated 24 kHz mono audio with
complete model, speaker, frontend, resampler, runtime, and generation provenance.

#### Scenario: VITS run is interrupted

- **WHEN** generation is canceled, paused, or the process stops during a segment
- **THEN** no partial audio is marked ready and an already verified Kokoro artifact remains unchanged
