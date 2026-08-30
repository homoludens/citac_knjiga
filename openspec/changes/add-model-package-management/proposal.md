## Why

The app can execute an installed model package but gives users no app-level way to import one or understand the installed model and import failures. Users need a safe path from an approved external release page to the existing verified offline installation flow.

## What Changes

- Add an app button that selects a model package through the Storage Access Framework and installs it through the existing `ModelPackageStore.importFromSaf()` verification path.
- Display whether a verified model package is installed, its package identity and version, and actionable import or installed-package failures.
- Add a **Get model package** action that opens a configured release page in an external browser; the app remains without the `INTERNET` permission and performs no download itself.
- Advertise only model packages that have passed the legal distribution gate and are authenticated by the publisher.
- Retain checksum, manifest schema, and runtime compatibility validation; app-private atomic installation; rollback to the last valid package; and the prohibition on executing arbitrary raw model files.
- Non-goals: an in-app network downloader, bundled model weights, bypassing the legal gate, and support for multiple TTS engines, which requires a separate change.

## Capabilities

### New Capabilities

- `model-package-management`: User-visible discovery, verified SAF import, installed-package identity and status, actionable failures, and legally gated external package acquisition.

### Modified Capabilities

None. There are currently no canonical capability specs under `openspec/specs` to modify.

## Impact

- Affects the Android model-management UI, SAF activity-result wiring, localized status and error text, and configuration of the external release-page URL.
- Reuses `ModelPackageStore` and app-private model storage; package validation, publication, rollback, and inference boundaries remain enforced by the existing runtime.
- Requires UI and integration coverage for missing, valid, invalid, incompatible, failed, and successfully replaced packages, plus verification that release manifests retain no network permission.
- Adds no model artifacts, downloader, network client, or additional TTS engine.
