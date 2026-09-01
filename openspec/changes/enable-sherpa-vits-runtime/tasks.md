## 1. Sherpa Runtime And Source Closure

- [x] 1.1 Pin a Sherpa-ONNX source revision, Android build flags, ABI list, Apache-2.0 notices, and complete source/dependency closure; verify standard and F-Droid builds contain only declared Sherpa native libraries.
- [x] 1.2 Add the Sherpa Kotlin/Java boundary to `tts-onnx` with deterministic lifecycle and cancellation handling; verify native sessions and buffers close on success, failure, and cancellation.
- [x] 1.3 Preserve `minSdk 30` and add API 33 Android qualification fixtures for arm64-v8a plus an equivalent API 33 development ABI; verify the old API 30/35/36 matrix is not an acceptance gate for this change.

## 2. Serbian VITS Package

- [x] 2.1 Fetch the pinned Hugging Face model revision in a disposable workspace and record source, conversion, model, token, and configuration hashes without committing raw checkpoints.
- [x] 2.2 Convert the Coqui VITS model into the Sherpa VITS file layout and validate tensor names, shapes, speaker identity, native sample rate, and deterministic package contents.
- [x] 2.3 Define the CC-BY-4.0 attribution, Apache-2.0 runtime notice, modification notice, package schema, and release documentation; verify all required links and hashes resolve.
- [x] 2.4 Add a VITS package slot to existing model management without replacing the last valid Kokoro package; verify altered, unsafe, or undeclared package entries are rejected.

## 3. Text And Audio Boundary

- [x] 3.1 Inspect the converted model vocabulary and implement the model-matched Serbian frontend for Cyrillic, declared Latin handling, punctuation, abbreviations, and unsupported numbers.
- [x] 3.2 Add golden tokenization/preprocessing tests and verify the Sherpa Kokoro/Piper frontend is never used for VITS input.
- [x] 3.3 Implement native 22,050 Hz validation and exactly-one deterministic resampling to 24,000 Hz mono; verify invalid native/final PCM is never published.
- [x] 3.4 Add VITS engine, model revision, speaker, frontend, resampler, runtime, and rate fields to new-audio provenance and generation identity without changing legacy rows.

## 4. Production Integration

- [x] 4.1 Add the smallest engine boundary exposing VITS only after package and API 33 qualification pass, retaining Kokoro as the default.
- [x] 4.2 Generate a new VITS segment through the existing bounded generation and atomic publication paths with no network access or parallel pipeline.
- [ ] 4.3 Verify switching engine preferences does not rewrite, delete, or invalidate existing audio and failed VITS import/generation preserves Kokoro state.

## 5. Qualification And Release

- [ ] 5.1 Run API 33 arm64-v8a offline generation and an equivalent API 33 target, recording correctness, timing, memory, interruption, recovery, and no-network evidence.
- [x] 5.2 Run JVM, Android, lint, standard/F-Droid release, source-closure, dependency, and attribution checks; verify the package is never fetched at runtime.
- [x] 5.3 Update `AGENT_README.md` and `DEPLOYMENT.md` with the exact Sherpa revision, model identity, license notices, API 33 qualification result, package restrictions, and rollback behavior.
