## 1. Candidate Identity, Source, and Legal Evidence

- [ ] 1.1 Add a machine-readable qualification manifest for `daremc86/sr-cv-vits` revision `83dc1e1b95d85b9f5602dc94909706fc83dfbc6`, speaker Dragana id `0`, native 22,050 Hz, and final 24,000 Hz, and verify identity mismatches fail before qualification proceeds
- [ ] 1.2 Implement pinned Hugging Face snapshot acquisition with resolved-commit verification, symlink/path-traversal rejection, complete source-file metadata, and SHA-256 recording, and verify a clean fetch reproduces the source manifest
- [ ] 1.3 Add a separate manifest-linked legal/source record covering model, code, datasets, speaker permission, conversion inputs, distributed artifacts, notices, and distribution decision, and verify missing or unresolved evidence produces `UNRESOLVED` or `BLOCKED`
- [ ] 1.4 Add redacted qualification evidence fixtures and gate-result serialization, and verify every gate is independently addressable as `PASS`, `FAIL`, or `UNRESOLVED` without accepting a different revision

## 2. Isolated Desktop Conversion and Model Contract

- [ ] 2.1 Define a locked disposable desktop conversion environment with converter, Python dependencies, ONNX tooling, OS/container identity, numeric environment, seeds, and thread settings, and verify Android build/runtime dependencies are absent
- [ ] 2.2 Implement checkpoint/configuration and converter-boundary inspection for ONNX inputs, outputs, speaker semantics, sample rates, operators, external data, limits, and determinism, and verify the report describes the actual candidate contract rather than Kokoro assumptions
- [ ] 2.3 Convert the pinned source into a self-contained standard ONNX graph and record byte hashes, sizes, and canonical manifest identity, and verify a second clean run is byte-identical or fails the conversion gate
- [ ] 2.4 Reject graphs requiring custom operators, network lookups, unreproducible randomness, or Android-incompatible boundaries, and verify each rejection names the offending contract or operator

## 3. VITS Package and Offline Validation

- [ ] 3.1 Define the strict `serbian-vits-model-package:1` schema and package identity calculation without changing the existing Kokoro schema, and verify the Kokoro positive and negative fixtures remain unchanged
- [ ] 3.2 Implement package creation for only the approved manifest, ONNX, optional external speaker tensor, model config, preprocessing/resampler metadata, notices, and attribution paths, and verify undeclared paths and raw checkpoints are never emitted
- [ ] 3.3 Implement package validation for safe paths, exact sizes/checksums, tensor/audio contracts, operator domains, external-data rules, legal status, and duplicate/unknown entries, and verify malformed-package fixtures fail closed
- [ ] 3.4 Implement app-private import, complete validation, checksum verification, atomic publication, and last-valid rollback for engine-qualified packages, and verify a failed VITS import cannot replace or remove the usable Kokoro package

## 4. Serbian Preprocessing, Resampling, and Corpus

- [ ] 4.1 Implement versioned platform-neutral `serbian-vits-v1` preprocessing with NFC cleanup, supported punctuation, deterministic Latin-to-Cyrillic mapping, protected spans, diagnostics, and chunk boundaries derived from the inspected tensor contract, and verify desktop and Android produce identical intermediate representations
- [ ] 4.2 Add deterministic Serbian number and abbreviation expansion for all declared corpus forms, and verify unsupported numeric syntax, abbreviations, mixed scripts, unknown characters, unknown tokens, and hard-limit inputs produce stable diagnostics rather than silent alteration
- [ ] 4.3 Create a license-safe self-authored corpus and committed expected-policy metadata covering Cyrillic, Latin/Cyrillic pairs, diacritics, digraphs, numbers, abbreviations, punctuation, mixed scripts, long inputs, and Dragana id `0`, and verify private user text is not embedded
- [ ] 4.4 Implement `resampler-22050-24000-v1` using the frozen 160/147 polyphase windowed-sinc contract and coefficient identity, and verify expected output lengths, finite float32 mono output, amplitude bounds, and deterministic repeated results
- [ ] 4.5 Add validation that each model call is checked at 22,050 Hz, resampled exactly once, and published only as 24,000 Hz mono audio, and verify downstream PCM16/codec validators accept valid output and reject invalid output

## 5. Parity and Audio Quality Evidence

- [ ] 5.1 Build a candidate-specific parity harness that runs identical declared inputs through desktop PyTorch, desktop ONNX, and Android ONNX without Kokoro wrappers or hard-coded Kokoro limits, and verify the Android sidecar contains vectors and numeric inputs but no source text or executable content
- [ ] 5.2 Freeze a named threshold declaration before formal evaluation and report per-vector input/speaker identity, shapes, lengths, finite/clipping status, waveform error, spectral similarity, environment hashes, and report checksum, and verify missing criteria or post-evaluation changes yield `UNRESOLVED`
- [ ] 5.3 Run desktop parity against the complete vector set with exact sample-count and index-only comparisons, and verify the first failing vector/metric causes a `FAIL` without padding, truncation, time alignment, or a second resampling
- [ ] 5.4 Define and freeze the Serbian listening rubric and evaluator procedure for intelligibility, naturalness, coverage, and Dragana identity, and verify every required corpus case has an auditable quality result independent of Kokoro recordings

## 6. Android Qualification Matrix

- [ ] 6.1 Add the candidate-specific Android ONNX session using only the approved self-contained package and pinned CPU runtime, and verify inference works with networking disabled and never loads PyTorch, checkpoints, scripts, or converter code
- [ ] 6.2 Freeze workload, correctness checks, performance and memory budgets, thermal policy, stability criteria, run counts, and measurement commands before device execution, and verify the declarations are present in the qualification evidence
- [ ] 6.3 Execute the complete parity and representative long/chunked narration workload on production `arm64-v8a` targets for API 30, API 35, and API 36, and verify each report includes device/build/runtime/package/preprocessing/resampler identities and limitations
- [ ] 6.4 Record cold load, warm generation, preprocessing, resampling, total wall time, RTF, PSS/RSS, temperature, thermal status, throttling, battery, crashes, ANRs, invalid outputs, interruptions, checkpoints, and recovery, and verify missing or substituted measurements leave the matrix `UNRESOLVED`
- [ ] 6.5 Produce the Android resource/stability gate result requiring every predeclared correctness, performance, memory, thermal, and stability criterion to pass on every required target, and verify no Kokoro performance threshold is reused as a VITS acceptance threshold

## 7. Fail-Closed Acceptance and Conditional Integration

- [ ] 7.1 Implement the acceptance summary as the sole promotion authority linking every gate to evidence, and verify any `FAIL` or `UNRESOLVED` result produces `REJECTED`/`NOT ACCEPTED` with no production VITS activation
- [ ] 7.2 Implement the minimal selectable engine boundary only behind an `ACCEPTED` summary, retaining Kokoro as the default and selectable backend, and verify rejected candidates expose no VITS package slot, preference, or generation path
- [ ] 7.3 Add engine-qualified package storage and a candidate-specific runtime boundary returning validated 24,000 Hz mono audio plus provenance, and verify VITS installation cannot collide with or retire the last valid Kokoro package
- [ ] 7.4 Extend generation identity and persisted provenance with engine, pinned model/revision, speaker, preprocessing, inference, native/final rates, resampler, and audio-processing identity, and verify equivalent VITS and Kokoro generations have different keys
- [ ] 7.5 Add the narrowly scoped Room 2-to-3 migration with nullable additive provenance fields and one persisted engine-preference row, and verify legacy rows remain unchanged, null fields mean legacy Kokoro, and restart/rollback/package retirement preserve provenance
- [ ] 7.6 Persist Kokoro as the default preference and route only new or explicitly regenerated audio through the selected accepted engine, and verify changing preference does not rewrite, delete, stale, or regenerate existing playable audio
- [ ] 7.7 Test generation failure, invalid output, package retirement, and rollback behavior, and verify no partial audio is published, prior verified audio remains intact, and the previous valid engine remains usable

## 8. Dependency, Release, and Project Documentation Closure

- [ ] 8.1 Record desktop converter and Android dependency source, version, checksum, license, attribution, notices, and SBOM closure, and verify every discrepancy blocks acceptance or release
- [ ] 8.2 Run locked offline Gradle checks, `scripts/validate_release_docs.py`, and F-Droid substitute checks with `--require-build`, and verify merged standard/F-Droid manifests retain no network permission, routine network API, model payload, or undeclared native library
- [ ] 8.3 Run the complete qualification and conditional-integration test suite with an externally supplied accepted package, and verify both accepted and rejection paths satisfy the release gates without changing existing Kokoro audio or keys
- [ ] 8.4 Update `DEPLOYMENT.md`, release/attribution/legal/package-compatibility documentation, and `AGENT_README.md` with exact outcome, identities, commands, hashes, device matrix, limitations, and rollback state, and verify rejected candidates are not described as available backends
