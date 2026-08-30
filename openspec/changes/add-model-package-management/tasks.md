## 1. Safe Contracts

- [x] 1.1 Define safe installed-package metadata and typed import-result/failure models containing only validated identity, version, checksums, compatibility, and runtime fields; verify `./gradlew :tts-onnx:test` compiles the public contract without exposing `File` or `Uri` to app state.
- [x] 1.2 Normalize store failures into stable source, storage, archive, manifest, checksum, compatibility, publication, no-valid-package, and unknown-error categories; verify unit tests map every failure category deterministically and never return a raw exception message.
- [x] 1.3 Add model status types for `VERIFIED`, `MISSING`, `INVALID`, `INCOMPATIBLE`, and `ERROR`, and map validated store results to a redacted diagnostics snapshot; verify tests cover every state and assert paths, URIs, archive entries, payloads, raw manifests, exceptions, credentials, and tokens are absent.
- [x] 1.4 Keep the existing single `AndroidTypedTextProofEngine` and ONNX runtime path as the only engine boundary, with no model weights or raw checkpoints added to source, fixtures, or resources; verify `python3 scripts/check_source_closure.py` reports no unexpected model artifacts.

## 2. Private Import and Verification

- [x] 2.1 Make SAF import open the provider stream once, stage it into an owner-scoped hidden temporary archive under app-private model storage, and retain no provider URI; verify tests cover a canceled/failed stream and assert the temporary candidate is removed in `finally`.
- [x] 2.2 Route the staged archive through the existing model-package verifier for schema v1, one manifest, safe relative paths, duplicate/undeclared-entry rejection, required roles, declared sizes, SHA-256 values, package identity, and model/preprocessing/runtime/Android API/ABI/tensor/audio compatibility; verify `ModelPackageStoreTest` covers valid, malformed, hostile-path, missing-role, altered-checksum, identity, and incompatible archives.
- [x] 2.3 Ensure raw checkpoint files and unverified archive contents cannot reach inference, while verified artifact reads remain private and revalidated; verify tests reject an undeclared checkpoint and confirm `OnnxTtsSession.open()` only succeeds through a verified manifest role.
- [x] 2.4 Return successful imports with safe metadata and failed imports with the stable redacted result without exposing the staged archive or source handle to the UI; verify the store/API tests inspect the returned object graph for no path or URI fields.

## 3. Atomic Storage and Recovery

- [x] 3.1 Add one process-level serialization mechanism plus a private file lock around imports, active-package reads, and publication, and define a verified read snapshot/session-open boundary; verify concurrent import/read tests show no partial archive or mixed metadata is observable.
- [x] 3.2 Implement the private transaction marker and complete-package-slot publication sequence: validate candidate, mark transaction, move the prior active archive to `last-valid.zip`, atomically install `active.zip`, sync/clear the marker, then clean stale temporary files; verify a successful replacement leaves both the new active and prior last-valid package valid.
- [x] 3.3 Implement startup and `activePackage()` marker recovery by validating both slots, retaining a completed valid active slot, restoring the valid previous slot when needed, and reporting `NO_VALID_PACKAGE` when neither validates; verify tests cover interrupted publication, each slot independently corrupt, both slots corrupt, and process-restart recovery.
- [x] 3.4 Preserve the previous active package through copy, validation, storage, move, cleanup, and rollback failures, and never publish a failed candidate; verify fault-injection tests assert the old package remains usable and no partial candidate is treated as active.

## 4. Runtime Snapshot Boundary

- [x] 4.1 Update `AndroidTypedTextProofEngine` and `OnnxTtsSession` to open a read-locked verified package snapshot and release the read lock after model and voice artifacts are opened; verify runtime tests show an import cannot mutate files during session opening.
- [x] 4.2 Ensure a replacement affects only the next generation while an already opened session finishes or cancels against its snapshot; verify a concurrent replacement test observes stable in-progress input and the new package on the next session.

## 5. SAF Picker and Diagnostics Lifecycle

- [x] 5.1 Add a visible `Import model package` action to `DiagnosticsAboutRoute` using a remembered `OpenDocument` launcher with advisory ZIP MIME filters; verify Compose/instrumentation coverage observes the picker launch and no-selection or unsupported-selection leaves status and active package unchanged.
- [x] 5.2 Wire the selected URI to the store on an IO coroutine with duplicate-import protection, busy state, and success refresh only after committed publication; verify UI tests observe disabled duplicate actions, in-progress status, and no premature `VERIFIED` state.
- [x] 5.3 Tie import jobs to route lifecycle so disposal cancels work while store cleanup still runs; verify a lifecycle test cancels an import and asserts no published partial archive or stale temporary candidate remains.
- [x] 5.4 Render safe installed identity/version/checksums, compatibility/runtime metadata, status, and category-specific next actions without paths or provider data; verify Compose tests cover missing, verified, invalid, incompatible, error, and failed-replacement states and show the old package as verified when it remains active.

## 6. External Acquisition and Distribution Gate

- [x] 6.1 Add build-time `MODEL_RELEASE_URL` configuration with an empty default, allowing a non-empty standard release destination only after release-tool approval while leaving debug and F-Droid variants empty unless policy explicitly permits them; verify variant configuration tests assert the expected values.
- [x] 6.2 Implement fail-closed release-URL validation requiring an absolute `http` or `https` URI, non-empty host, no user-info, and no malformed or alternate scheme; verify unit tests accept supported URLs and reject empty, `file`, `content`, `intent`, `javascript`, credential-bearing, and malformed values.
- [x] 6.3 Add `Get model package` only for a validated configured URL, dispatching `ACTION_VIEW` after resolving an external browser and never fetching, redirecting, downloading, or installing; verify intent tests observe one browser-handled external action and unavailable/no-browser tests observe no navigation or network attempt.
- [ ] 6.4 Enforce publisher authentication and explicit legal-clearance evidence in release metadata/tooling before advertising a package or destination, while keeping local SAF verification independent and never labeling a local package legally cleared; verify release-gate tests reject missing or self-declared evidence and accept only trusted recorded evidence.

## 7. Localization and Integration Coverage

- [ ] 7.1 Add localized strings for import/acquisition actions, busy/success states, all stable failure categories, status states, and category-appropriate recovery actions in the supported resource locales; verify resource compilation and locale UI tests find every required user-visible message.
- [ ] 7.2 Extend diagnostics snapshot/export tests to cover safe metadata, every status and failure mapping, retained-active replacement failures, and redaction of paths, URIs, archive details, raw exceptions, and secrets; verify `./gradlew :app:test` passes the complete diagnostics suite.
- [ ] 7.3 Extend Compose and instrumentation tests for picker cancellation, unsupported selection, busy state, valid import, invalid/incompatible/storage/publication failures, successful replacement, and old-package retention; verify `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest` passes or reports the device test outcome without adding model artifacts.
- [ ] 7.4 Cover the configured external action in localized UI/integration tests, including unavailable configuration, malformed URL, missing browser, and successful external dispatch; verify the tests assert no in-app request or download API is invoked.

## 8. Offline Release Closure

- [ ] 8.1 Keep standard-release and F-Droid merged manifests free of `INTERNET`, `ACCESS_NETWORK_STATE`, and other routine network permissions; verify `./gradlew verifyOfflineReleaseManifests` passes for both merged release manifests.
- [ ] 8.2 Extend source/dependency closure checks to reject downloader/network clients, WebView or background fetch paths, local model payloads, and undeclared file-based runtime inputs while preserving the locked single ONNX dependency; verify `python3 scripts/check_source_closure.py` and the relevant dependency audit pass.
- [ ] 8.3 Run final standard and F-Droid release checks, including manifest, payload, metadata, legal-gate, and source-closure validation; verify `python3 scripts/check_fdroid.py --require-build` and the project release verification command pass without a model package in either APK.
