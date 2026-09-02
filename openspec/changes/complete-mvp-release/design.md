## Context

The implementation already has separate PDF, EPUB, generation, playback, export,
model, and release tooling. Existing change-local specifications describe most
of that behavior, while the remaining work is evidence-heavy and crosses module
boundaries. The PDF report enables PdfBox, but a real Poco picker/import failure
still needs diagnosis.

## Goals / Non-Goals

**Goals:**

- Preserve the existing offline and private-storage architecture.
- Make PDF source failures observable and actionable without weakening safety.
- Produce reproducible qualification, portability, device-matrix, and release evidence.
- Leave one active OpenSpec change that contains all remaining release work.

**Non-Goals:**

- Adding network downloads, OCR, a second PDF pipeline, or a new audiobook format.
- Checking model weights, raw checkpoints, signing keys, or generated private data into Git.
- Calling the current experimental VITS result production-qualified without the required evidence.

## Decisions

- **Use the existing boundaries.** Fix PDF behavior in the existing SAF repository,
  preview service, and diagnostic formatter instead of bypassing staging or adding
  a second picker path. This preserves the established threat model.
- **Separate implementation from evidence.** Run qualification and portability
  through deterministic scripts/tests where possible, and store only redacted
  reports and hashes in the repository.
- **Treat unavailable hardware as a deviation.** Do not replace missing Android
  targets with unsupported claims; record the exact target, reason, and impact.
- **Keep release signing external.** The release script receives a keystore path
  and secrets out-of-band, verifies the resulting APKs, and never copies
  credentials into the project.
- **Archive superseded planning after consolidation.** The new change is the
  implementation source of truth; completed and superseded change directories are
  moved to the OpenSpec archive without rewriting application code.

## Risks / Trade-offs

- [Real PDF provider behavior differs by file manager] -> Capture URI/source
  category and add a device regression for local storage plus an actionable
  provider failure; never fall back to network access.
- [Required Android targets are unavailable] -> Record missing evidence as a hard
  release deviation rather than marking the matrix complete.
- [External players reject the chosen container or metadata] -> Validate output
  before claiming success and preserve the internal WAV/M4A artifact on failure.
- [Legal or signing inputs remain unavailable] -> Keep publication fail-closed and
  produce unsigned artifacts only as explicitly non-release local evidence.
