## 1. Data And Project Lifecycle

- [x] 1.1 Add the persistent deleting project state and per-segment estimated word count through a Room schema migration; verify existing databases migrate and existing projects remain readable.
- [x] 1.2 Define the complete app-owned artifact inventory for a project and a root-contained cleanup policy; verify unit tests never target the original SAF URI or external export files.
- [x] 1.3 Implement project-scoped deletion coordination with a persistent deleting marker and exclusive publication lock; verify an in-flight generation worker cannot publish after deletion.
- [x] 1.4 Add transactional project deletion, WorkManager cancellation, playback stop, and startup cleanup recovery; verify source, canonical, cover, audio, temporary, Room, and playback state are removed after restart.

## 2. Durable Regeneration

- [x] 2.1 Define one engine-independent generation request for a complete book or selected chapter over existing PDF/EPUB narration blocks; verify both source formats produce the same request shape.
- [x] 2.2 Implement the durable Kokoro coordinator and adapt the existing VITS coordinator behind the shared generation boundary; verify both engines create valid persisted runs and segments.
- [x] 2.3 Implement chapter/book invalidation that removes targeted audio and resets targeted segments before queueing; verify unrelated chapters and projects remain unchanged.
- [x] 2.4 Add chapter-level and whole-book regeneration actions with explicit destructive confirmation; verify canceled, failed, and successful actions expose correct states and retry paths.

## 3. Progress And Compact UI

- [x] 3.1 Calculate and persist approximate Unicode-aware word estimates during segment planning; verify totals exclude skipped content and remain stable after process restart.
- [x] 3.2 Aggregate completed and total estimated words for chapters and books from Room state; verify percentages increase only for ready segments and fall back safely for legacy rows.
- [x] 3.3 Remove complete narration-text rendering from library and book detail screens and add an explicit sampled/full preview surface; verify large imported documents do not expand the main screen with all text.
- [x] 3.4 Add localized generation, deletion, regeneration, and progress strings with accessibility semantics; verify Compose tests cover queued, running, paused, failed, canceled, and completed states.

## 4. Direct GitHub Model Downloads

- [x] 4.1 Define pinned Kokoro and VITS release descriptors containing HTTPS asset URLs, repository/release identity, filenames, versions, sizes, and outer SHA-256 values; verify configuration rejects arbitrary URLs and inconsistent metadata.
- [x] 4.2 Add the required network permission and update standard/F-Droid manifest, source-closure, privacy, and release-policy checks; verify network is allowed only for model asset downloads and generation remains offline.
- [x] 4.3 Implement cancellable HTTPS streaming to private temporary storage with WorkManager connectivity constraints and byte/percentage progress; verify cancellation, disconnect, short response, and oversized response remove temporary files.
- [x] 4.4 Connect completed downloads to the existing Kokoro/VITS package validators and atomic package slots; verify outer checksum, manifest, compatibility, and artifact failures preserve the prior active package.
- [x] 4.5 Add separate Kokoro/VITS download actions and status UI; verify downloading, verifying, installed, failed, canceled, and offline states are visible without exposing private paths or credentials.

## 5. Integration And Documentation

- [ ] 5.1 Run end-to-end PDF and EPUB flows covering import, compact library display, chapter regeneration, whole-book regeneration, approximate progress, playback recovery, and deletion; verify the original external files remain unchanged.
- [ ] 5.2 Run migration, JVM, Android, lint, source-closure, dependency, and release-manifest checks for the changed modules; verify no model payload or generated audio enters APK artifacts.
- [ ] 5.3 Update `DEPLOYMENT.md` and `AGENT_README.md` with direct-download configuration, pinned release hashes, network policy, deletion/regeneration behavior, progress semantics, rollback, and verification commands.
