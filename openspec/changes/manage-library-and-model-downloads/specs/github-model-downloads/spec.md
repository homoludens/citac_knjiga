## Purpose

Allows the app to download pinned Kokoro and VITS model packages directly from
trusted GitHub Release assets and install them automatically only after complete verification.

## ADDED Requirements

### Requirement: Download pinned GitHub release assets

The application SHALL offer separate Kokoro and VITS model downloads using
application-pinned HTTPS GitHub Release URLs, asset names, versions, expected
sizes, and outer SHA-256 values. It SHALL reject arbitrary user-provided model
URLs and SHALL report transfer progress and cancellation.

#### Scenario: User downloads a known model

- **WHEN** the user starts a configured Kokoro or VITS model download
- **THEN** the app downloads the pinned release asset to private temporary storage and reports progress until verification begins

#### Scenario: Network download fails

- **WHEN** the connection fails, the user cancels, or the asset is unavailable
- **THEN** the temporary download is removed, the failure is shown, and the currently installed model remains unchanged

### Requirement: Verify and install downloaded models

The app SHALL verify the complete downloaded archive against its pinned outer
SHA-256, package identity, manifest, declared artifact hashes, engine
compatibility, Android API/ABI, and runtime requirements before automatic
installation. Installation SHALL be atomic and SHALL preserve the prior valid
package on any verification or publication failure.

#### Scenario: Downloaded asset is valid

- **WHEN** a configured release asset passes all outer and package checks
- **THEN** it is installed into the correct Kokoro or VITS slot and becomes available to the engine selector

#### Scenario: Downloaded asset is altered or incompatible

- **WHEN** the bytes, manifest, package identity, artifacts, or compatibility do not match the pinned contract
- **THEN** installation fails closed and the previous valid package remains active

### Requirement: Network and release policy

Network access SHALL be limited to the configured GitHub Release download
endpoints and SHALL not download code, documents, or runtime dependencies. Model
packages SHALL remain external to APK artifacts, and release documentation SHALL
declare the network permission and download trust model for every distribution variant.

#### Scenario: App is offline

- **WHEN** no network is available
- **THEN** existing installed models remain usable and the app reports that downloads are unavailable without affecting local generation
