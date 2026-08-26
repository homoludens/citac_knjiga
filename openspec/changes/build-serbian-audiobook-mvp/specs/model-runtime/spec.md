## Purpose

Defines how a compatible Serbian Kokoro model is packaged, verified, compared with the reference implementation, and executed offline on supported Android devices.

## ADDED Requirements

### Requirement: Versioned model package
The system SHALL accept an independently distributed model package containing the inference model, Dragana voice/style data, token vocabulary, configuration, preprocessing compatibility version, license and attribution material, and cryptographic checksums.

#### Scenario: Valid package import
- **WHEN** the user selects a complete compatible model package
- **THEN** the system verifies every declared artifact and records the package identity and compatibility versions before enabling inference

#### Scenario: Invalid package import
- **WHEN** a package is incomplete, corrupted, unsupported, or fails a checksum
- **THEN** the system rejects it without replacing the last valid installed package and explains the validation failure

### Requirement: Reference parity gate
The model release process MUST compare FP32 ONNX output with the pinned PyTorch CPU reference across representative Serbian test vectors before declaring a package Android-compatible.

#### Scenario: Parity passes
- **WHEN** all preprocessing identities and declared numerical and audio similarity thresholds pass
- **THEN** the package is marked eligible for Android validation

#### Scenario: Parity fails
- **WHEN** any required vector exceeds a declared threshold or produces invalid audio
- **THEN** the package is rejected and the failing vector and metric are reported

### Requirement: Offline Android inference
The application SHALL generate 24 kHz mono audio locally from compatible token, style, and speed inputs without uploading text, tokens, model data, or audio.

#### Scenario: Offline generation
- **WHEN** a compatible model is installed and the device has no network connection
- **THEN** the application generates playable audio using only local resources

### Requirement: Generation provenance
The system SHALL record the model package checksum, voice checksum, preprocessing version, inference settings, and audio-processing version for every completed segment.

#### Scenario: Model package changes
- **WHEN** a different model package becomes active
- **THEN** segments made with the previous provenance remain identifiable as stale and are not silently treated as current

### Requirement: Device qualification
The Android proof-of-concept SHALL measure model load time, real-time factor, peak memory, CPU utilization, thermal behavior, and battery use on the reference device before whole-book implementation proceeds.

#### Scenario: Reference-device benchmark
- **WHEN** the representative sustained benchmark completes on a Poco F3 or declared equivalent
- **THEN** the results and the explicit proceed, optimize, or stop decision are stored with the model package evaluation

