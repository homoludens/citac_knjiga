## Why

The Serbian VITS candidate is distributable under the model's CC-BY-4.0 terms
and can use the Apache-2.0 Sherpa-ONNX runtime. The previous qualification did
not establish this path because it tested Sherpa as a drop-in Kokoro frontend
and required an unnecessarily broad Android API matrix.

## What Changes

- Add a source-built, offline Sherpa-ONNX Android runtime for the Serbian VITS
  model while preserving `minSdk 30`.
- Convert the pinned `daremc86/sr-cv-vits` model into a checked, Sherpa-compatible
  VITS package with CC-BY attribution and package integrity metadata.
- Use the model's actual Serbian tokenizer and preprocessing contract instead
  of Sherpa's unrelated Kokoro frontend.
- Validate native 22,050 Hz output and resample it exactly once to the existing
  24,000 Hz mono audio contract.
- Qualify the runtime on Android 13/API 33 and an equivalent API 33 ABI target,
  with offline generation and reproducible source/dependency evidence.
- Expose VITS beside Kokoro only after package validation, retaining Kokoro as
  the default and preserving existing audio provenance.

## Capabilities

### New Capabilities

- `sherpa-vits-runtime`: Offline Serbian VITS packaging, Sherpa-ONNX Android
  execution, API 33 qualification, engine selection, and provenance.

### Modified Capabilities

- None.

## Impact

- Affects `tts-onnx`, model package validation, Serbian preprocessing, generation
  provenance and keys, Android release dependency/source closure, and model
  attribution documentation.
- `minSdk` remains API 30. API 33 is the required Android qualification level;
  API 30/35/36 are not required acceptance targets for this change.
- No network access is added. Raw checkpoints and conversion tooling remain
  desktop-only; Android receives only the validated Sherpa package.
