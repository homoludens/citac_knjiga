# Context

`DiagnosticsAboutRoute` is the existing model/diagnostics surface and already has an activity-result launcher for diagnostics export. `AppContainer` owns one `ModelPackageStore`, and `AndroidTypedTextProofEngine` resolves the verified active package before opening the existing single ONNX runtime path. `ModelPackageStore.importFromSaf()` already copies a provider stream to a private temporary ZIP, validates the package, and publishes `active.zip`/`last-valid.zip` below `filesDir`.

The missing boundary is app-level orchestration: SAF selection, visible import state and diagnostics, safe installed metadata, external release-page navigation, and serialized publication/runtime access. Model packages remain optional external artifacts; no model weights are bundled.

# Goals / Non-Goals

Goals:

- Add import and acquisition actions to the existing diagnostics screen without adding a new activity or network client.
- Keep provider data, temporary candidates, archives, and verified runtime inputs inside app-private storage.
- Make active-package state recoverable after interrupted publication or process death, while preserving the last valid package on every failed replacement.
- Expose only verified manifest identity, checksums, compatibility, and stable redacted diagnostics.
- Preserve the current one-engine ONNX runtime integration and offline release boundary.

Non-goals:

- No in-app downloader, WebView, model browser, background network work, or `INTERNET` permission.
- No legal approval inferred from a checksum, schema, compatibility result, or self-declared publisher field.
- No bundled model payloads and no support for multiple TTS engines; another engine requires a separate change.
- No Room schema or migration for model packages.

# Decisions

1. **SAF and lifecycle.** `DiagnosticsAboutRoute` owns a `rememberLauncherForActivityResult(OpenDocument)` launcher filtered to `application/zip` (the filter is advisory; validation remains authoritative). A selected URI is passed directly to `ModelPackageStore.importFromSaf()` on an IO coroutine. Cancellation or no result does nothing. The screen disables duplicate imports, shows an in-progress state, and updates status only after a successful result. Route/job disposal cancels work; store cleanup must still run in `finally`, so a canceled or killed import cannot publish a partial candidate.

2. **Store boundary.** Reuse the public `importFromSaf()` result, which already returns `InstalledModelPackage`; do not expose its `File` or the provider `Uri` to the UI. If asynchronous app code needs a typed result, add only a small success/failure wrapper carrying safe metadata and `ModelPackageFailureCode`. The store must open the provider stream once, copy it to an owner-scoped hidden temporary file under the model-package private directory, close it, then validate the temporary archive. It must never inspect or execute provider contents or retain the URI.

3. **Atomic publication and recovery.** Treat `active.zip` and `last-valid.zip` as complete-package slots, never as partially written files. Serialize imports and package reads/publication with one process lock plus a private file lock where needed. Before publication, validate the candidate completely. During publication, record a small private transaction marker, move the old valid active archive to `last-valid.zip`, atomically move the candidate to `active.zip`, sync/clear the marker, and remove stale temporary files only after the committed state is safe. On startup or `activePackage()`, recover any marker: validate both slots, keep a valid active package if the commit completed, otherwise restore the valid previous slot. If only the previous slot validates, restore it; if neither validates, return `NO_VALID_PACKAGE` without enabling inference. A failed copy, validation, move, or cleanup must leave the previous active package usable.

4. **Runtime concurrency.** Keep the existing `AndroidTypedTextProofEngine` and `OnnxTtsSession` as the only runtime path. The store provides a read-locked verified package snapshot/session-open boundary, and publication takes the write side, so import cannot change the archive while model and voice artifacts are being opened. The lock need not cover synthesis after the ONNX session is constructed. A generation already in progress finishes or is canceled using its opened session; a replacement affects the next generation.

5. **Safe status view model.** Extend the validated store result only with safe fields that are read from the verified manifest, such as runtime identity/version and preprocessing compatibility ID/version. `DiagnosticsAboutSnapshotBuilder` maps that result to a model-specific `VERIFIED`, `MISSING`, `INVALID`, `INCOMPATIBLE`, or `ERROR` state. It must not parse a path or URI itself. The view model contains no archive path, URI, archive entry, payload, raw manifest text, or exception. If a replacement fails while an older package remains active, keep the active state `VERIFIED` and show the separate redacted latest-import diagnostic; never present the failed candidate as active.

6. **Stable errors and redaction.** Map store failures deterministically: source open failures to `SOURCE_UNAVAILABLE`; private copy/space failures to `STORAGE`; malformed ZIP/manifest/integrity failures to `INVALID_ARCHIVE`, `INVALID_MANIFEST`, or `CHECKSUM_MISMATCH`; contract failures to `INCOMPATIBLE`; publication/rollback failures to `PUBLICATION`; and no recoverable package to `NO_VALID_PACKAGE`. Unknown operational failures map to `ERROR`. UI text, local diagnostics, and exports use only these stable categories plus bounded safe values, validated package IDs, and validated checksums. They never include raw exception messages, source paths, provider URIs, archive paths/entries, document text, credentials, or tokens.

7. **External release action.** Configure a build-time `MODEL_RELEASE_URL` per variant, defaulting to empty. The standard release may receive a destination only when release tooling approves it; debug and F-Droid variants remain empty unless explicitly configured by policy. Validate without opening a connection: require an absolute URI with exactly `https` or `http`, a non-empty host, no user-info, and no malformed or alternate scheme such as `file`, `content`, `intent`, or `javascript`. For a valid URL, use only `ACTION_VIEW` and first resolve an external browser. If validation or resolution fails, show unavailable state and do nothing. The app performs no fetch, download, redirect handling, or installation; the browser owns network access.

8. **Publisher/legal gate.** Release metadata and release tooling, not the Android importer, establish publisher authentication and recorded legal clearance. The gate must reject an advertised package or configured release destination unless trusted publisher evidence and explicit legal-clearance evidence are present. A package checksum, valid schema, compatible runtime, or `publisher` string alone proves none of those things. Local SAF import independently performs all integrity and compatibility checks and never labels a locally imported package legally cleared. Existing app-only release separation and offline policy remain mandatory.

9. **Tests and manifests.** Add store tests for canceled/failed streams, stale transaction recovery, both-slot corruption, concurrent import/read behavior, and preservation of the prior package. Add view-model/error tests for every status/category and redaction, URL tests for accepted/rejected schemes and browser-unavailable behavior, and Compose/instrumentation coverage for picker cancellation, busy/success/failure states, replacement, and external intent dispatch. Keep merged standard-release and F-Droid-release manifest checks asserting no `INTERNET` or other routine network permission, and retain source/dependency checks that reject downloader/network APIs and APK model payloads. No model artifact is added to tests or the APK.

# Risks / Trade-offs

- A transaction marker and serialization add a small amount of private file-management complexity, but are safer than relying on two independent renames during a crash.
- Import requires temporary space in addition to the active package; storage failures are explicit and preserve the old package.
- Browser navigation depends on another installed application and cannot guarantee that a release page is reachable; this is intentional to preserve offline execution.
- A valid local package can still be legally uncleared. Keeping that distinction means the app cannot provide an in-app legal/release catalog without a separately approved metadata channel.
- File locks do not protect against arbitrary external filesystem tampering, so every runtime opening and status read continues to revalidate the package contract.

# Migration Plan

1. Keep the existing `filesDir/model-packages/active.zip` and `last-valid.zip` names and make startup recovery understand the new transaction marker; delete only stale hidden candidates after recovery.
2. Add safe manifest metadata and locking at the existing store/runtime boundary, then wire the diagnostics launcher and view model to the existing `AppContainer` instance.
3. Add variant URL configuration, release-gate metadata checks, UI/localization tests, store/runtime tests, and merged-manifest checks before enabling a release URL.
4. There is no Room migration: model state is currently file-backed and no database change is needed. Only if implementation inspection proves that persisted model-management state must move into Room should a separate migration design be created.
