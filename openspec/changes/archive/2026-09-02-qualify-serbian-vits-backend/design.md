## Context

The candidate is Hugging Face `daremc86/sr-cv-vits` at revision
`83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`. It declares one Serbian speaker,
Dragana with id `0`, and native 22,050 Hz output. The current Android runtime
is Kokoro-specific, uses a verified private model package, and publishes mono
24,000 Hz audio. The current legal inventory and Kokoro parity evidence do not
qualify this candidate.

This change is therefore a qualification first. Raw checkpoints and conversion
tools may be used only in an isolated desktop environment. Android integration
is not started unless every required gate passes.

## Goals / Non-Goals

**Goals:**

- Produce reproducible, inspectable evidence for the exact pinned candidate.
- Prove legal/source closure, safe conversion, model contract, preprocessing,
  parity, Serbian quality, and Android resource behavior before promotion.
- Keep rejection a valid outcome that leaves Kokoro unchanged.
- If accepted, add only the minimum engine selection, provenance, package, and
  resampling changes needed to support VITS beside Kokoro.

**Non-Goals:**

- Making VITS the default or claiming it is smaller, faster, or better.
- Running PyTorch, Python, checkpoints, scripts, or converters on Android.
- Changing existing audio, downstream sample rates, exports, or playback.
- Generalizing the Kokoro package contract before VITS is qualified.

## Decisions

### 1. Use a staged qualification manifest

Create one canonical manifest containing the candidate id, immutable revision,
speaker identity, native rate, final rate, gate order, tool versions, and hashes.
Fetch the exact revision into a disposable workspace and record every source
file, resolved commit, size, and SHA-256. Floating refs, symlinks, traversal,
missing files, or substitutions fail identity qualification.

Legal evidence is a separate manifest-linked record. It must cover model code,
datasets, speaker permission, conversion inputs, notices, licenses, and the
distribution decision. A model card, checksum, or self-declared license is not
legal clearance. Missing or contradictory evidence records `UNRESOLVED` or
`BLOCKED` and stops promotion.

### 2. Keep conversion desktop-only and reproducible

Run conversion in a locked disposable desktop environment. Record the converter
revision, Python and dependency lock, ONNX tools, OS/container identity,
numeric settings, seeds, thread settings, command, and input/output hashes.
A second clean run must produce byte-identical artifacts and the same canonical
manifest identity. Otherwise the conversion gate fails.

Inspect the actual graph rather than assuming Kokoro inputs. Record every input
and output name, type, shape, speaker semantics, sample rate, operator domain,
external-data use, randomness, and resource limit. The approved graph must be
self-contained standard ONNX, runnable by the pinned Android CPU runtime, and
free of custom operators or network lookup.

### 3. Use a separate VITS package contract

Do not alter `serbian-model-package:1`. A passing candidate uses a strict
`serbian-vits-model-package:1` schema with only manifest, self-contained ONNX,
declared configuration, preprocessing/resampler metadata, notices, attribution,
and optional declared speaker data. The package contains no checkpoints,
PyTorch files, converter source, scripts, executable helpers, ONNX sidecars, or
undeclared entries.

Package identity, artifact sizes, SHA-256 values, graph contract, speaker id,
legal status, and evidence hashes are validated before private atomic import.
VITS packages use an engine-qualified storage slot so they cannot replace or
retire the last valid Kokoro package.

### 4. Freeze deterministic Serbian preprocessing

Define a platform-neutral versioned policy before formal evaluation. It uses
Unicode NFC, deterministic whitespace and control cleanup, canonical Serbian
Cyrillic, an explicit case-aware Latin-to-Cyrillic mapping, protected spans,
and an inspectable diagnostic for unsupported input.

Numbers and abbreviations are expanded by pinned pure data/rules covering every
form used by the quality corpus. Unsupported syntax is diagnosed rather than
passed as raw digits or silently dropped. Chunk boundaries come from the
inspected VITS contract, not Kokoro limits. Desktop and Android must emit the
same intermediate representation and record its identity in generation keys.

### 5. Preserve the downstream audio contract

Keep native VITS output at 22,050 Hz and convert it once to the existing
24,000 Hz mono contract using a versioned deterministic resampler. Freeze the
coefficient table, ratio, output-length rule, boundary behavior, and checksum
before parity. Validate native output before resampling and final output before
PCM/codec publication. Include native rate, final rate, resampler identity, and
audio-processing identity in provenance and generation identity.

Changing all downstream audio to 22,050 Hz is rejected because it would alter
existing playback, export, and cache assumptions without helping qualification.

### 6. Build candidate-specific parity and quality evidence

Use a candidate-specific harness for desktop reference, desktop ONNX, and
Android ONNX. Do not reuse Kokoro wrappers, token limits, style rows, or
thresholds. Freeze the vector set and numeric acceptance criteria before formal
evaluation. Check input and speaker identity, shapes, exact sample counts,
finite values, clipping, waveform error, spectral similarity, and any declared
duration outputs. Do not pad, truncate, time-align, or resample a second time.

The listening corpus is separate from numeric parity and is license-safe. It
covers Cyrillic, the declared Latin policy, Serbian diacritics and digraphs,
numbers, abbreviations, punctuation, long inputs, chunking, and Dragana id `0`.
Freeze the rubric before evaluation and record intelligibility, naturalness,
coverage, and speaker identity results.

### 7. Qualify the required Android matrix

Run the approved package on production `arm64-v8a` targets for API 30, 35, and
36. Each report records device/build/ABI/runtime/package/preprocessing/resampler
identity, workload, commands, and limitations. Correctness includes complete
parity vectors and long/chunked narration with networking disabled.

Freeze performance, memory, thermal, battery, and stability budgets before
measurement. Record cold load, warm generation, preprocessing, resampling, RTF,
PSS/RSS, temperature, throttling, crashes, ANRs, invalid output, interruption,
and recovery. A missing or substituted target or criterion is `UNRESOLVED`.
No Kokoro measurement is reused as a VITS acceptance threshold.

### 8. Promote only through an acceptance summary

The summary links every gate to evidence and records `ACCEPTED` or `REJECTED`.
Any `FAIL` or `UNRESOLVED` result leaves Kokoro as the only production path and
adds no VITS preference, package slot, or runtime code.

After acceptance only, add a small engine boundary. Kokoro remains the default
and selectable engine. Existing audio remains playable and its provenance is
unchanged when preference changes. New or explicitly regenerated audio records
engine, exact model revision, speaker, preprocessing, inference settings,
native/final rates, resampler, and audio-processing identity. All these values
participate in generation identity so VITS and Kokoro outputs cannot collide.

The current database does not expose every required engine and rate field as a
first-class record. Perform a schema audit first. If existing immutable package
metadata cannot preserve the requirement, use one additive Room migration with
nullable provenance fields and a persisted Kokoro-default preference. Legacy
rows remain untouched; null fields mean legacy Kokoro. Do not regenerate old
audio during migration.

Running PyTorch on Android is rejected because it violates the offline package
boundary and expands the attack and dependency surface. Immediate VITS default
selection is rejected because legal, parity, quality, and device evidence are
not yet established.

## Risks / Trade-offs

- [The candidate lacks legal or speaker permission evidence] -> Record the
  missing item and reject promotion; do not infer clearance from the model card.
- [Conversion needs unsupported operators or is nondeterministic] -> Fail the
  conversion gate and add no Android workaround.
- [Serbian numbers or Latin input remain unreliable] -> Keep the policy
  versioned, diagnose unsupported forms, and block the quality gate.
- [Resampling harms timing or quality] -> Compare the frozen native/final
  contract and reject if declared length or quality criteria fail.
- [One required Android target is unavailable or exceeds its budget] -> Leave
  the matrix unresolved or failed and retain Kokoro.
- [Engine-specific storage or migration loses provenance] -> Use separate
  package slots, additive fields, restart/rollback tests, and no destructive
  migration.
- [Qualification artifacts leak source text or executable content] -> Keep
  reports redacted, vector sidecars numeric and text-free, and validate package
  paths and contents before use.

## Migration Plan

1. Create the pinned source, legal, conversion, graph, preprocessing, corpus,
   parity, and device manifests in desktop-only tooling. Keep raw payloads and
   generated artifacts outside the repository.
2. Run all gates in order and retain reports even when the result is rejection.
   A rejection changes no Android, Room, Gradle, or production runtime code.
3. If every gate passes, add the separate package schema, engine-qualified
   store, candidate runtime, resampler, preference, provenance, and generation
   identity changes. Re-run offline, source-closure, F-Droid, parity, and
   instrumentation checks with the accepted package supplied externally.
4. Roll back by disabling VITS selection and retaining the last valid package
   for each engine. Failed imports or generations publish no partial package or
   audio and do not alter old provenance.
5. Update `DEPLOYMENT.md`, legal/attribution/package documentation, and
   `AGENT_README.md` with the exact accepted or rejected outcome and evidence.
