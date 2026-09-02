## Purpose

Defines a fail-closed, evidence-based qualification path for the exact pinned Serbian VITS candidate before it can affect production audio generation.

## ADDED Requirements

### Requirement: Exact candidate and staged qualification identity
The qualification record MUST identify the candidate as Hugging Face model `daremc86/sr-cv-vits` at revision `83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`, and MUST evaluate gates in a declared sequence with separate evidence and a `PASS`, `FAIL`, or `UNRESOLVED` result for each gate. Evidence for another revision or an unrecorded substitution SHALL NOT qualify this candidate.

#### Scenario: Different model revision is supplied
- **WHEN** qualification input names a model, revision, or derived artifact that does not resolve to `daremc86/sr-cv-vits` at `83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`
- **THEN** qualification records an identity failure and does not proceed as qualification of the pinned candidate

#### Scenario: A staged gate is unresolved
- **WHEN** a gate has no sufficient evidence or has an `UNRESOLVED` result
- **THEN** later gates may be recorded for investigation but the candidate cannot receive an accepted qualification result

### Requirement: Legal and source closure with distribution decision
The qualification MUST establish authoritative source provenance for the pinned model, its training data and voice identity, conversion inputs, and distributable artifacts; record each applicable license, attribution, modification notice, and unresolved review; and make an explicit `ALLOWED` or `BLOCKED` distribution decision. A schema-valid manifest, checksum, or parity result SHALL NOT be treated as legal clearance. The record MUST preserve the candidate's declared Serbian speaker identity, Dragana with speaker id `0`, unless evidence explicitly disproves or revises that declaration before acceptance.

#### Scenario: Source or rights review remains open
- **WHEN** any required source, license, attribution, speaker-permission, modification, or distribution review is missing or unresolved
- **THEN** the legal gate is `UNRESOLVED` or `BLOCKED`, the open item is named, and no model or derived audio package is approved for distribution

#### Scenario: Legal closure supports distribution
- **WHEN** every required source and rights record is complete, attribution and modification notices are present, and the explicit distribution decision is `ALLOWED`
- **THEN** the candidate may proceed to the next qualification gate without implying that any different model revision is cleared

### Requirement: Trusted deterministic conversion and declared model contract
The qualification MUST produce reproducible, checksum-identifiable ONNX and package artifacts from the exact pinned source through a trusted conversion record. Approved artifacts SHALL contain only declared model data, configuration, voice data, preprocessing contract, attribution, licenses, and integrity metadata, with no raw checkpoint, arbitrary executable code, or undeclared payload. The record MUST declare and validate tensor names, types, shapes, voice or speaker selection, preprocessing inputs, output format, native sample rate of 22,050 Hz, and the deterministic resampling contract to the downstream 24,000 Hz mono audio contract.

#### Scenario: Conversion or contract validation fails
- **WHEN** conversion is not reproducible from the pinned revision, an artifact checksum changes unexpectedly, an undeclared payload is present, or a tensor, voice, preprocessing, or sample-rate contract check fails
- **THEN** the conversion gate is `FAIL`, the offending identity or contract field is reported, and no artifact is approved for Android or production use

#### Scenario: Approved package is reproduced
- **WHEN** the trusted conversion record, declared artifact checksums, package contents, tensor contract, Dragana speaker selection, 22,050 Hz native output, and 24,000 Hz downstream conversion contract all validate
- **THEN** the package is marked eligible for parity qualification but is not yet enabled as a production backend

### Requirement: Deterministic desktop and Android output parity
The qualification MUST compare the approved artifact with a trusted desktop reference using the same declared preprocessing, inputs, voice or speaker identity, inference settings, and determinism controls, and MUST compare Android output with the approved desktop artifact on the same parity vectors. The evidence MUST include all declared intermediate and audio metrics, exact output-shape or sample-count checks, invalid-output checks, and the frozen acceptance criteria; every required vector and criterion MUST pass for the parity gate to pass.

#### Scenario: Desktop parity diverges
- **WHEN** any required vector differs beyond a declared criterion, has different required output structure or sample count, uses different preprocessing or speaker identity, or produces invalid audio
- **THEN** desktop parity is `FAIL`, the first failing vector and metric are recorded, and Android parity cannot promote the candidate

#### Scenario: Android parity is deterministic and complete
- **WHEN** Android runs the approved package against the complete parity evidence using the declared deterministic controls and every vector, identity, and metric meets its criterion
- **THEN** Android parity is `PASS` for the tested target and the report records the target identity, package identity, and reproducible evidence checksum

### Requirement: Serbian quality corpus and input policy
The qualification MUST maintain an inspectable Serbian quality corpus that exercises Cyrillic input, an explicit deterministic policy for Latin input, numbers, abbreviations, punctuation, and the declared Dragana speaker identity. The corpus MUST record the effective input policy and expected handling for each covered case, and quality evaluation MUST provide evidence for intelligibility, naturalness, coverage, and speaker identity without treating the model card's preferred Cyrillic input or stated lack of number support as an acceptance result.

#### Scenario: Latin policy is not defined
- **WHEN** a Latin Serbian sample has no declared deterministic policy for acceptance, conversion, diagnostic rejection, or equivalent handling
- **THEN** the corpus gate is `UNRESOLVED` and the candidate is not accepted

#### Scenario: Required Serbian cases are evaluated
- **WHEN** the corpus covers Cyrillic, the declared Latin policy, numbers, abbreviations, punctuation, and speaker identity and the recorded quality criteria pass for all required cases
- **THEN** the Serbian quality gate is `PASS` and its corpus version and evidence identity are recorded

### Requirement: Representative Android correctness, resource, and stability evidence
The qualification MUST obtain representative Android evidence on API 30, API 35, and API 36 targets for the approved production artifact. Each target report MUST cover correctness, performance, process memory, thermal behavior, and stability under the declared workload, and MUST identify the device or emulator, ABI, OS build, runtime configuration, workload, measurement method, and limitations. Performance, memory, thermal, and stability acceptance criteria MUST be selected and recorded before qualification in design evidence rather than inferred from historical observations or invented in this requirement. An unavailable or substituted target SHALL leave this gate `UNRESOLVED`.

#### Scenario: API 30, 35, and 36 evidence is incomplete
- **WHEN** any required API target is unavailable, substituted by another API level or architecture, or lacks one of the required correctness, performance, memory, thermal, or stability measurements
- **THEN** Android qualification remains `UNRESOLVED` and the candidate cannot be accepted

#### Scenario: Representative matrix satisfies declared criteria
- **WHEN** API 30, API 35, and API 36 reports contain reproducible production-artifact measurements and observations and all predeclared qualification criteria pass
- **THEN** the Android resource and stability gate is `PASS` without making an unsupported comparison to Kokoro

### Requirement: Evidence-based acceptance or rejection
The qualification MUST publish a gate summary that links every result to inspectable evidence and records an explicit `ACCEPTED` or `REJECTED` outcome. Rejection is a valid complete outcome. The candidate MUST be rejected, or remain non-integrated, when any required gate is `FAIL` or `UNRESOLVED`; no production VITS backend, package activation, or user preference SHALL be introduced in that state.

#### Scenario: At least one gate fails or remains unresolved
- **WHEN** the legal, conversion, desktop parity, Android parity, Serbian quality, or Android matrix gate is `FAIL` or `UNRESOLVED`
- **THEN** the summary records `REJECTED` or `NOT ACCEPTED` with the blocking evidence and the application retains its existing production backend without VITS integration

#### Scenario: Every required gate passes
- **WHEN** the exact candidate identity is confirmed and every required gate has a complete evidence-backed `PASS`
- **THEN** the summary may record `ACCEPTED` and only the accepted candidate identity becomes eligible for the conditional integration behavior

### Requirement: Conditional engine selection, provenance, and cache identity
If and only if qualification is `ACCEPTED`, the application SHALL expose and persist a selectable VITS engine preference while retaining Kokoro as a selectable backend. Changing the preference MUST NOT rewrite, delete, or alter the provenance of existing audio, and existing valid audio MUST remain playable. New or explicitly regenerated audio MUST record the selected engine, exact model identity and revision, voice or speaker identity, preprocessing identity, native and final sample rates, inference settings, and audio-processing identity. Engine, model, preprocessing, voice, settings, and audio-processing changes MUST participate in generation identity so VITS and Kokoro outputs cannot collide and only the affected audio is regenerated.

#### Scenario: Accepted preference changes with existing audio
- **WHEN** an accepted installation changes the preference from Kokoro to VITS or from VITS to Kokoro while verified audio already exists
- **THEN** the existing files remain playable with their original provenance unchanged, and no regeneration is forced solely by the preference change

#### Scenario: VITS audio is newly generated or regenerated
- **WHEN** a new segment is generated or an existing segment is explicitly regenerated under the accepted VITS preference
- **THEN** its persisted provenance identifies VITS, the exact pinned model and revision, the selected speaker, preprocessing, native 22,050 Hz output, final 24,000 Hz mono output, inference settings, and audio processing, and its generation identity differs from the corresponding Kokoro identity

### Requirement: Safe offline production execution and downstream audio contract
Production Android execution MUST use only the approved package artifacts and declared input contract, MUST perform the versioned deterministic conversion from native 22,050 Hz output into the existing 24,000 Hz mono downstream contract, and MUST operate without network access. Android MUST NOT execute arbitrary raw checkpoints, PyTorch content, conversion tools, scripts, or other executable model content, and a package or runtime that requires network access SHALL fail closed.

#### Scenario: Unsafe or network-dependent package is presented
- **WHEN** a package contains a raw checkpoint, PyTorch dependency, converter or script, undeclared executable content, or inference attempts to access the network
- **THEN** installation or generation is rejected, no audio is published, and the existing Kokoro path remains available

#### Scenario: Accepted VITS generation is offline
- **WHEN** an accepted VITS package is used with networking disabled and valid declared inputs
- **THEN** generation completes from local approved artifacts only and publishes audio that satisfies the versioned 24,000 Hz mono downstream contract
