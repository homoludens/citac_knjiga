## Why

Imported books currently cannot be removed or regenerated from the library, and
generation progress is difficult to understand for longer PDF/EPUB documents.
Model packages also require manual browser download and SAF import, which makes
first-time Kokoro/VITS setup unnecessarily cumbersome.

## What Changes

- Add deletion of imported PDF/EPUB projects and all app-owned derived data, while never deleting the original external file.
- Add regeneration actions for an individual chapter and for an entire book.
- Stop and remove existing generated audio before regeneration, then queue the selected Kokoro or VITS engine.
- Show approximate word-based generation progress and separate model-download progress.
- Remove full imported text from the main library/detail UI and keep it behind an explicit preview/details interaction.
- Add direct HTTPS downloads of pinned Kokoro and VITS GitHub Release assets, followed by automatic checksum, manifest, compatibility, and atomic installation checks.
- Preserve the currently installed model when a download, verification, or installation fails.

## Capabilities

### New Capabilities

- `library-lifecycle`: Delete imported books and their app-owned files safely from the library.
- `audiobook-regeneration`: Regenerate one chapter or an entire imported PDF/EPUB using the selected engine.
- `generation-progress`: Present approximate word and percentage progress for durable generation.
- `compact-library-ui`: Keep full document text out of the main library and detail screens.
- `github-model-downloads`: Download pinned Kokoro/VITS release assets directly from GitHub and install them only after verification.

### Modified Capabilities

None. No synced main capability specs exist; these are new contracts for the next implementation change.

## Impact

Affected areas include Room project and generation state, app-private source,
canonical-text, cover, audio, and temporary cleanup, WorkManager coordination,
Kokoro/VITS generation orchestration, library/detail Compose UI, progress models,
Android network permission and download transport, GitHub release configuration,
model-package verification, release documentation, and F-Droid policy review.

Direct model downloads use only application-pinned HTTPS release URLs and asset
SHA-256 values. Model packages remain separate from APKs and signing secrets.
