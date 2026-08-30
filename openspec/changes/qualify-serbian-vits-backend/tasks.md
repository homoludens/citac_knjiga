## 1. Qualification Identity and Gates

- [ ] 1.1 Define the canonical qualification manifest for `daremc86/sr-cv-vits` revision `83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`, Dragana speaker id `0`, native 22,050 Hz, final 24,000 Hz mono, ordered gates, tool versions, and evidence hashes; verify manifest-schema tests reject a missing or altered identity field.
- [ ] 1.2 Fetch only the pinned revision into a disposable desktop workspace and record the resolved commit, every source path, size, and SHA-256 while rejecting floating refs, symlinks, traversal, missing files, and substitutions; verify a source-manifest test fails closed for each identity violation.
- [ ] 1.3 Create a manifest-linked legal/source record covering model code, training data, voice identity and permission, conversion inputs, licenses, attributions, modification notices, and an explicit `ALLOWED` or `BLOCKED` distribution decision; verify incomplete or contradictory evidence produces `UNRESOLVED` or `BLOCKED` and never legal clearance.
- [ ] 1.4 Implement the staged gate record with separate `PASS`, `FAIL`, and `UNRESOLVED` results and evidence links, stopping promotion when a required gate is missing, failed, or unresolved; verify a gate-runner test prevents an unresolved earlier gate from yielding an accepted candidate.
- [ ] 1.5 Keep raw source payloads, checkpoints, and generated qualification artifacts outside the repository while retaining inspectable redacted manifests and reports; verify the source-closure check reports no model payload or secret in tracked project files.

## 2. Isolated Conversion and VITS Package

- [ ] 2.1 Define a locked disposable desktop conversion environment containing the converter revision, Python and dependency lock, ONNX tooling, OS or container identity, numeric settings, seeds, thread settings, and command; verify a clean-environment manifest comparison reports identical toolchain identity.
- [ ] 2.2 Convert only the pinned source in the isolated environment and run a second clean conversion; verify both runs produce byte-identical ONNX and package artifacts, matching hashes, and one canonical manifest identity or the conversion gate is `FAIL`.
- [ ] 2.3 Inspect the actual ONNX graph and record every input and output name, type, shape, speaker semantics, sample-rate behavior, operator domain, external-data use, randomness, and resource limit; verify graph-inspection tests reject custom operators, external data, undeclared inputs, network lookup, or a runtime-incompatible graph.
- [ ] 2.4 Define the separate `serbian-vits-model-package:1` schema containing only the manifest, self-contained ONNX, declared configuration, preprocessing and resampler metadata, notices, attribution, licenses, and optional declared speaker data; verify schema tests reject `serbian-model-package:1` substitution and every undeclared entry.
- [ ] 2.5 Validate package identity, entry paths, sizes, SHA-256 values, graph contract, Dragana id `0`, legal status, evidence hashes, native rate, final rate, and preprocessing contract before eligibility; verify altered checksums, malformed metadata, path traversal, and contract mismatches produce no eligible package.
- [ ] 2.6 Reject raw checkpoints, PyTorch files, converter sources, scripts, executable helpers, ONNX sidecars, arbitrary code, and undeclared payloads from the package; verify a hostile-package test reports the offending entry and never exposes the package to Android or production storage.

## 3. Serbian Preprocessing and Corpus

- [ ] 3.1 Freeze a versioned platform-neutral preprocessing policy for Unicode NFC, control and whitespace cleanup, canonical Serbian Cyrillic, case-aware Latin-to-Cyrillic handling, protected spans, and diagnostic handling of unsupported input; verify golden-vector tests produce identical intermediate representations for desktop and Android.
- [ ] 3.2 Add pinned pure data and rules for every number and abbreviation form used by the corpus, including punctuation behavior, and diagnose unsupported syntax instead of passing raw digits or silently dropping text; verify preprocessing tests assert each expected expansion or diagnostic.
- [ ] 3.3 Build an inspectable license-safe corpus covering Cyrillic, the declared Latin policy, Serbian diacritics and digraphs, numbers, abbreviations, punctuation, long inputs, chunking, and Dragana speaker id `0`; verify corpus validation fails when any required category, expected handling, or speaker identity is absent.
- [ ] 3.4 Derive chunk boundaries from the inspected VITS contract rather than Kokoro limits and record corpus version, effective input policy, and expected handling for every case; verify long-input fixtures show the declared chunks and reject an inherited Kokoro boundary.
- [ ] 3.5 Produce redacted, text-free numeric vector sidecars and preprocessing evidence with the policy identity included; verify artifact inspection finds no unapproved source text or executable content and preserves reproducible vector hashes.

## 4. Deterministic Audio Contract

- [ ] 4.1 Freeze the versioned 22,050-to-24,000 Hz resampler identity, coefficient table, ratio, output-length rule, boundary behavior, mono handling, and checksum before parity evaluation; verify the resampler manifest validator rejects any missing contract field.
- [ ] 4.2 Implement the declared deterministic resampling once after validating native 22,050 Hz output; verify impulse, tone, length, finite-value, and checksum tests produce the declared 24,000 Hz mono result.
- [ ] 4.3 Validate native output before resampling and final output before PCM or codec publication, recording native rate, final rate, resampler identity, and audio-processing identity; verify invalid native or final samples are rejected without published audio.
- [ ] 4.4 Prohibit padding, truncation, time alignment, or a second resampling step in parity and publication paths; verify sample-count and call-trace tests show exactly one declared conversion and fail on any extra audio transformation.

## 5. Candidate-Specific Parity and Listening Quality

- [ ] 5.1 Freeze the candidate-specific parity vectors, deterministic controls, numeric acceptance criteria, output-shape and sample-count rules, invalid-output rules, and listening rubric before formal evaluation without reusing Kokoro wrappers, thresholds, or style rows; verify the evaluation runner refuses to start without a versioned approved criteria artifact.
- [ ] 5.2 Compare the trusted desktop reference with desktop ONNX using identical preprocessing, inputs, Dragana speaker id `0`, inference settings, and determinism controls; verify desktop parity tests report every declared intermediate and audio metric and identify the first failing vector when any criterion fails.
- [ ] 5.3 Evaluate the approved package on Android ONNX against the same complete parity vectors and criteria with networking disabled; verify Android parity tests record target, package, preprocessing, resampler, and evidence checksums and fail on identity, shape, sample-count, finite-value, clipping, waveform, or spectral divergence.
- [ ] 5.4 Run the separate license-safe listening corpus for intelligibility, naturalness, coverage, speaker identity, Latin policy, numbers, abbreviations, punctuation, long inputs, and chunking; verify the listening report records the frozen rubric, every case result, candidate identity, and no unsupported comparison to Kokoro.
- [ ] 5.5 Publish desktop, Android, audio-contract, and listening reports linked from the gate manifest; verify evidence-integrity tests resolve every link and mark the relevant parity or quality gate `FAIL` for any missing, substituted, or untraceable result.

## 6. Predeclared Android Resource and Stability Matrix

- [ ] 6.1 Freeze approved performance, memory, thermal, battery, and stability criteria and pass/fail or unresolved rules before collecting measurements, without deriving thresholds from historical observations or Kokoro; verify the criteria manifest has an approval identity and an earlier timestamp than every measurement.
- [ ] 6.2 Define the representative production `arm64-v8a` matrix for API 30, API 35, and API 36 with device or emulator, OS build, runtime configuration, approved package, preprocessing and resampler identities, workload, measurement commands, and limitations; verify matrix validation rejects an unavailable, substituted, or wrong-ABI target.
- [ ] 6.3 Execute the frozen workload with networking disabled and record correctness, cold load, warm generation, preprocessing, resampling, RTF, process memory, PSS/RSS, temperature, throttling, battery, crashes, ANRs, invalid output, interruption, and recovery for each target; verify report-schema tests reject any target missing a required observation or method.
- [ ] 6.4 Apply only the predeclared criteria to the complete API 30, 35, and 36 reports; verify the Android resource and stability gate is `PASS` only when every target and criterion passes, otherwise records `FAIL` or `UNRESOLVED` with the blocking evidence and makes no Kokoro comparison.

## 7. Acceptance or Explicit No-Integration Rejection

- [ ] 7.1 Publish a gate summary linking identity, legal, conversion, desktop parity, Android parity, Serbian quality, and Android matrix results to inspectable evidence and recording exactly `ACCEPTED` or `REJECTED`; verify summary validation rejects an outcome with a missing gate, evidence link, or blocking reason.
- [ ] 7.2 Implement the valid rejection path for any `FAIL` or `UNRESOLVED` gate: retain the report and rejection reason, add no VITS backend, package activation, engine preference, Room migration, or production runtime code, and keep Kokoro available; verify a rejection fixture observes no VITS integration artifacts and existing Kokoro audio remains playable with unchanged provenance.
- [ ] 7.3 Permit acceptance only for the exact pinned identity after every required gate is complete and `PASS`, without making default, size, speed, quality, or Kokoro-relative claims; verify an acceptance fixture exposes only the recorded candidate identity as eligible for conditional integration.

## 8. Acceptance-Only Engine Integration

- [ ] 8.1 After an `ACCEPTED` summary, audit existing audio, package, provenance, and generation schemas for the required engine and rate fields and decide whether immutable metadata already suffices; verify the audit artifact selects either no migration or one minimal additive migration based on recorded schema evidence.
- [ ] 8.2 If the audit requires it, add one additive Room migration with nullable provenance fields and a persisted Kokoro-default preference, leaving legacy rows untouched and treating nulls as legacy Kokoro; verify migration tests preserve row values and perform no old-audio regeneration.
- [ ] 8.3 Add the smallest engine boundary that exposes selectable VITS beside Kokoro only for an accepted candidate, with Kokoro remaining the default; verify accepted-state tests show both engines while rejected-state tests show no VITS engine or preference.
- [ ] 8.4 Add engine-qualified package slots and last-valid behavior so a VITS package cannot replace or retire the last valid Kokoro package; verify slot and rollback tests keep each engine's valid package independently usable after failed import or replacement.
- [ ] 8.5 Persist the selected engine preference without rewriting, deleting, or regenerating existing audio solely because the preference changes; verify switching Kokoro and VITS leaves existing files playable and their original provenance unchanged.
- [ ] 8.6 Record engine, exact model and revision, Dragana speaker, preprocessing identity, native and final sample rates, inference settings, resampler, and audio-processing identity for new or explicitly regenerated VITS audio; verify provenance tests assert all fields and distinguish VITS output from corresponding Kokoro output.
- [ ] 8.7 Include engine, model, voice, preprocessing, settings, native/final rates, resampler, and audio-processing identities in generation keys so outputs cannot collide and only affected audio regenerates; verify key tests produce distinct Kokoro and VITS identities and stable repeat keys.

## 9. Offline Runtime and Release Closure

- [ ] 9.1 Restrict Android inference to a verified `serbian-vits-model-package:1` and its declared ONNX/input contract, rejecting raw checkpoints, PyTorch content, converters, scripts, executable helpers, and undeclared files before generation; verify unsafe-package tests publish no audio and retain the Kokoro path.
- [ ] 9.2 Enforce offline generation and fail closed when package installation or inference requires network access; verify instrumentation with networking disabled completes accepted local generation to valid 24,000 Hz mono audio and records no network request.
- [ ] 9.3 Lock and audit dependencies so desktop-only conversion and PyTorch tooling cannot enter Android while the approved Android ONNX runtime remains source-closed; verify dependency and source-closure checks reject downloader, PyTorch, converter, script, and undeclared file-based runtime paths.
- [ ] 9.4 Run standard-release and F-Droid validation for merged manifests, permissions, dependencies, package payloads, metadata, legal evidence, offline behavior, and source closure; verify `python3 scripts/check_fdroid.py --require-build` and the project release checks pass without embedding a VITS package or network capability.
- [ ] 9.5 Update deployment, legal/attribution/package, and project technical documentation with the exact accepted or rejected outcome, evidence identities, package restrictions, rollback behavior, and offline/F-Droid requirements; verify documentation validation finds the outcome and linked evidence without undisclosed performance claims.
