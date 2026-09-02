## Purpose

Controls signed artifact creation and final publication so the application is
released only when legal, technical, portability, and device evidence is complete.

## ADDED Requirements

### Requirement: Signed release artifacts

The project SHALL build standard and F-Droid release artifacts with an external
production keystore, verify package metadata, signing schemes, certificate
identity, payload closure, permissions, notices, and checksums, and SHALL keep
signing credentials outside the repository.

#### Scenario: Signed release verification passes

- **WHEN** release artifacts are built with valid external signing credentials
- **THEN** both required artifacts pass signature, payload, dependency, source-closure, legal-document, and checksum verification

#### Scenario: Signing or release verification fails

- **WHEN** credentials are missing or any artifact gate fails
- **THEN** publication is blocked and no artifact is represented as a releasable build

### Requirement: Final publication gate

Publication SHALL require passing legal clearance, model and Android parity,
recovery, external-player export, capability-audit, OpenSpec, and device-review
gates. Optional model packages SHALL remain separate from application artifacts.

#### Scenario: All release gates pass

- **WHEN** every required gate has verified evidence
- **THEN** the project may publish the signed application artifacts and the release manifest

#### Scenario: Any hard gate remains unresolved

- **WHEN** legal evidence, qualification, portability, device coverage, or audit evidence is incomplete
- **THEN** the final gate fails closed and records the blocking deviations
