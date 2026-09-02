## Context

The app already stores imported PDF/EPUB sources and derived artifacts below
app-private storage, persists projects, chapters, narration blocks, generation
runs, audio segments, playback positions, and export jobs in Room, and executes
durable generation through WorkManager. Library state is derived from Room flows.
Kokoro and VITS currently have separate runtime/package boundaries, while model
acquisition is SAF-based and the existing release action only opens a browser.

## Goals / Non-Goals

**Goals:**

- Add safe project deletion and chapter/book regeneration without orphaned files or worker races.
- Make generation progress durable and understandable in approximate words.
- Keep primary library screens compact while retaining explicit text preview.
- Download pinned Kokoro/VITS GitHub Release assets directly and install them atomically.
- Preserve the existing verified model when a download or installation fails.

**Non-Goals:**

- Deleting the original external PDF/EPUB selected through Android storage.
- Keeping old generated audio playable during regeneration.
- Downloading arbitrary URLs, model code, documents, or runtime dependencies.
- Bundling model packages in APKs or adding OCR.

## Decisions

### Project lifecycle and deletion

Add a persistent deleting lifecycle marker to the project state and a project-
scoped exclusive operation lock shared by deletion and generation publication.
Deletion first stops the playback queue and cancels the project’s unique
WorkManager jobs, then marks the project as deleting. The worker and publication
boundary re-check that marker while holding the same lock, preventing an in-flight
worker from publishing after deletion.

Room rows are removed with the existing foreign-key cascades. Before or during
that transaction, the deletion coordinator collects only canonical paths below
the app-private root: source, cover, canonical text, audio, diagnostics, and
project-owned temporary files. External export destinations and the original
SAF URI are never deletion targets. If filesystem cleanup is interrupted, the
deleting marker and startup reconciliation finish cleanup on the next launch.

### Shared durable regeneration

Introduce one engine-independent generation request for a project or chapter.
The request reads the already imported narration blocks, creates the selected
engine’s segments, and schedules one durable run. Kokoro and VITS remain separate
engine implementations behind that boundary. PDF and EPUB differ only in their
imported source/provenance, not in generation behavior.

For regeneration, targeted segments are marked stale, their generated files and
ready metadata are removed, and the new run is queued. Because the product
decision is not to keep old audio, the selected scope is unavailable to playback
until replacement segments are validated and published. Other chapters remain
untouched for chapter-level regeneration.

### Word progress

Persist an estimated word count on each planned audio segment during generation
planning. The estimate uses Unicode-aware word counting over narratable source
text and is explicitly approximate. Room-backed progress sums planned words for
the selected scope and completed words for ready segments; failed or pending
segments do not count as complete. Existing segment-count progress remains a
diagnostic fallback for legacy rows without estimates.

Generation status and word totals are derived from the same durable rows, so UI
updates after process restart do not depend on an in-memory job.

### Compact UI

Keep complete document text in the imported IR and canonical files, but remove
full chapter-text rendering from the library and book detail compositions. Show
metadata, chapter rows, progress, storage, playback, regenerate, delete, and
failure actions. Text remains available through an explicit preview/details
surface, with sampled text as the default for large documents.

### Direct GitHub model downloads

Replace the browser-only model acquisition action with separate pinned descriptors
for Kokoro and VITS. Each descriptor contains a fixed HTTPS GitHub Release asset
URL, repository/release identity, filename, package version, expected size, and
outer SHA-256. Descriptors are application inputs, not user-editable URLs; a new
release therefore requires an app update.

Use a WorkManager download job with a connected-network constraint and progress
updates. The transport uses HTTPS and streams into a private temporary file. After
transfer, the job verifies size and outer SHA-256, then invokes the existing
engine-specific package verifier. Only a fully verified package reaches atomic
publication. The Kokoro and VITS package slots remain independent, and a failed
replacement leaves the active package and its rollback slot unchanged.

The direct download path requires `INTERNET` in both distribution variants. It is
limited to the pinned GitHub asset hosts/paths and is never used for generation,
document import, or runtime dependency acquisition.

### Schema and migration

Add the deletion lifecycle state and per-segment estimated word count through a
Room schema migration from the current database version. Existing projects remain
valid: their source paths and segment counts continue to work, and word progress
falls back to the available aggregate estimate until regeneration repopulates
segment estimates.

### Verification strategy

Cover deletion with Room/file cleanup, active-worker race, playback stop, cancel,
restart, and external-source-preservation tests. Cover regeneration for chapter
and book scopes, both engines, failure cleanup, and progress persistence. Cover
download success, cancellation, checksum mismatch, incompatible package,
offline behavior, progress, and old-package retention. Add UI tests for compact
rendering, confirmations, actions, and localized progress/status messages.

## Risks / Trade-offs

- [Deletion races with a worker or Media3 queue] -> Persist a deleting marker and use one project-scoped lock at every publication and playback-binding boundary.
- [Removing old audio makes failed regeneration immediately unavailable] -> Make the destructive behavior explicit in confirmation text and provide retry/resume actions; source and imported text remain safe.
- [Approximate words differ from actual spoken words] -> Label the value approximate and never use it as an audio-integrity claim.
- [Large GitHub assets fail on mobile networks] -> Stream to private temporary storage, expose cancellation/progress, validate before install, and retain the current package on failure.
- [GitHub release assets or paths change] -> Pin URL, release identity, filename, size, and SHA-256 in the app; require an app update for a new asset.
- [Network permission affects F-Droid policy] -> Declare the limited download purpose, keep all runtime/document operations offline, and include the permission and trust model in release documentation.
