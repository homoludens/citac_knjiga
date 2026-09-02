## Purpose

Defines the conditional integration of the Serbian VITS model through the
Apache-2.0 Sherpa-ONNX runtime on Android while retaining the existing Kokoro
backend and offline behavior.

## ADDED Requirements

### Requirement: Sherpa runtime and Android baseline

The application MUST use a pinned, source-auditable Sherpa-ONNX runtime for
VITS inference, with Apache-2.0 notices and complete dependency/source closure.
The Android application MUST retain `minSdk 30`; qualification MUST execute on
Android 13/API 33 with a production-supported ABI and an equivalent API 33
test target. No runtime dependency may download code, models, or data.

#### Scenario: Runtime is assembled

- **WHEN** a standard or F-Droid release is built
- **THEN** Sherpa-ONNX native libraries, notices, ABI configuration, and source
  provenance are present without network permissions or undeclared binaries

### Requirement: Serbian VITS package and attribution

The VITS package MUST identify the exact pinned
`daremc86/sr-cv-vits` revision, Dragana speaker, native 22,050 Hz output,
Sherpa-compatible model files, preprocessing/token metadata, SHA-256 values,
and CC-BY-4.0 attribution and modification notices. Raw PyTorch checkpoints,
converter scripts, and undeclared files MUST be rejected before installation.

#### Scenario: Valid package is installed

- **WHEN** the exact package contains only validated Sherpa VITS entries and
  complete attribution
- **THEN** it is stored in the VITS slot without replacing the last valid Kokoro
  package

#### Scenario: Unsafe or altered package is installed

- **WHEN** a package has a changed identity/checksum, raw checkpoint, executable,
  undeclared entry, or missing attribution
- **THEN** installation fails closed and existing Kokoro state remains unchanged

### Requirement: Model-matched Serbian text frontend

VITS inference MUST use the tokenizer and preprocessing contract that matches
the converted model, including Serbian Cyrillic and the declared handling of
Latin text, punctuation, abbreviations, and unsupported numbers. Sherpa's
Kokoro/Piper frontend MUST NOT be substituted for the VITS model frontend
unless parity evidence proves identical tokenization.

#### Scenario: Serbian text is generated

- **WHEN** valid supported Serbian text is submitted offline
- **THEN** the exact model-matched token sequence is passed to Sherpa and no
  characters are silently dropped

### Requirement: Audio contract and provenance

Native VITS output MUST be finite mono PCM at 22,050 Hz and MUST be converted
exactly once through the pinned deterministic resampler to finite mono 24,000 Hz
PCM before publication. New VITS audio MUST record engine, model revision,
speaker, preprocessing, native/final rates, resampler, runtime settings, and
audio-processing identity in its provenance and generation key.

#### Scenario: Invalid output is returned

- **WHEN** native or final samples violate the declared format or finite-value
  contract
- **THEN** no audio artifact is published

### Requirement: Conditional engine selection

VITS MUST be selectable only when its exact package is validated and the API 33
qualification gate passes. Kokoro MUST remain the default and existing valid
audio MUST remain playable and retain its original provenance when the selected
engine changes.

#### Scenario: VITS is not qualified

- **WHEN** package or API 33 qualification is missing or fails
- **THEN** the app exposes no usable VITS selection and continues using Kokoro

#### Scenario: Qualified VITS is selected

- **WHEN** a validated package is selected and new audio is generated
- **THEN** generation uses Sherpa VITS offline and produces distinct VITS
  provenance and cache identity without rewriting existing Kokoro audio
