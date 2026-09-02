## Purpose

Define a safe, visible way to import, inspect, replace, and acquire compatible offline model packages without exposing private data or adding in-app networking.

## ADDED Requirements

### Requirement: User-visible model package import

The application SHALL show a visible `Import model package` action on its model-management or diagnostics surface. Activating the action MUST open the Android Storage Access Framework document picker restricted to supported model-package archive files, including ZIP model-package files. A cancellation, no selection, or unsupported selection SHALL leave the active package and its status unchanged and SHALL not report a successful import.

#### Scenario: User selects a supported model archive
- **WHEN** the user activates `Import model package` and selects a supported model-package archive in the system picker
- **THEN** the application starts a local import attempt and presents a busy or equivalent in-progress state without claiming that the package is active yet

#### Scenario: User cancels or selects an unsupported document
- **WHEN** the user cancels the picker or the selected document is not a supported model-package archive
- **THEN** the application leaves the current active package unchanged and reports no successful import

### Requirement: Verified private package import

The application MUST copy the selected archive into app-private temporary storage before inspecting or activating it and MUST use the existing model-package safety contract for verification. Verification SHALL require the versioned `serbian-model-package` schema v1, one valid manifest, safe package-relative paths, no duplicate or undeclared archive entries, all required model-package roles, matching declared sizes and SHA-256 values, a matching package identity, and compatibility with the supported model, preprocessing, runtime, Android API, ABI, tensor, and audio contracts. The application MUST discard a candidate that fails any required check and MUST NOT use its provider URI or unverified contents for inference.

#### Scenario: Complete compatible package passes verification
- **WHEN** a selected archive contains the required schema, manifest, declared artifacts, checksums, identity, and supported runtime declarations
- **THEN** verification completes successfully and the candidate becomes eligible for atomic activation

#### Scenario: Package integrity or compatibility check fails
- **WHEN** a selected archive is malformed, incomplete, altered, schema-invalid, checksum-invalid, or incompatible with the supported runtime contract
- **THEN** the candidate is rejected and discarded from temporary storage without becoming executable or active

### Requirement: Atomic activation and last-valid recovery

After successful verification, the application SHALL replace the active package as one committed state and SHALL retain the last valid package until the replacement is committed. A copy, verification, storage, or publication failure MUST preserve the last valid package as the active usable package. If an active package later becomes invalid, the application MUST recover the retained last valid package when one exists; if none exists, it MUST report that no valid package is installed and MUST not enable inference.

#### Scenario: Valid package replaces the active package
- **WHEN** a verified candidate is imported while a valid package is active
- **THEN** the candidate becomes active as a complete package, and the previously active valid package remains available as the last-valid recovery package

#### Scenario: Replacement fails after a valid package exists
- **WHEN** copying, verification, storage, or publication of a replacement fails
- **THEN** the previous valid package remains active and the failed candidate is not partially visible as an installed package

### Requirement: Safe installed-package status

The model status shown in the application SHALL distinguish `VERIFIED`, `MISSING`, `INVALID`, `INCOMPATIBLE`, and `ERROR` states. `VERIFIED` status MUST expose only safe identity and compatibility metadata, including package ID, package version, package identity SHA-256, model and voice SHA-256 values, preprocessing compatibility version, and runtime identity/version. `MISSING` SHALL mean that no valid active package exists; `INVALID` SHALL identify archive, manifest, or integrity failure; `INCOMPATIBLE` SHALL identify a runtime-contract failure; and `ERROR` SHALL identify that status could not be determined because of an operational failure. The status view MUST never expose filesystem paths, provider URIs, model contents, or secrets.

#### Scenario: Verified package metadata is displayed
- **WHEN** the active package passes validation
- **THEN** the status shows `VERIFIED` together with its safe package identity, version, checksums, preprocessing compatibility, and runtime metadata, while omitting storage paths and payload contents

#### Scenario: No valid package or a failed candidate is present
- **WHEN** no valid package exists, or the latest import fails due to invalid, incompatible, or operational conditions
- **THEN** the status shows the corresponding `MISSING`, `INVALID`, `INCOMPATIBLE`, or `ERROR` state and does not present the failed candidate as verified

### Requirement: Actionable redacted import diagnostics

Every failed import or recovery attempt SHALL produce a user-visible diagnostic with a stable failure category and a next action appropriate to the category, such as selecting another archive, freeing private storage, retrying, or obtaining a compatible package. Diagnostics, local logs, and user exports MUST NOT disclose source paths, provider URIs, archive paths, document text, raw exceptions, credentials, tokens, or other secrets. Safe categorical codes, bounded numeric values, package identifiers, and validated checksums MAY be shown when available.

#### Scenario: Malformed archive produces a safe recovery message
- **WHEN** import fails because the archive or manifest is malformed or fails integrity validation
- **THEN** the application identifies the validation category, tells the user to select another trusted package or retry, and omits the source URI, filesystem path, archive entry path, and raw exception

#### Scenario: Storage or publication failure produces a safe recovery message
- **WHEN** import cannot finish because private storage or package publication is unavailable
- **THEN** the application tells the user to free space or retry, preserves the last valid package, and emits no path, secret, or exception detail

### Requirement: External model-package acquisition

The application SHALL show a `Get model package` action when an external release destination is configured. For a well-formed supported external release URL, activating the action MUST hand the URL to an external browser and MUST NOT fetch the page, download a package, or install anything in the application. When the URL is absent, malformed, unsupported, or no external browser can handle it, the application SHALL show an unavailable state and SHALL not attempt navigation or a network request.

#### Scenario: Configured release URL opens externally
- **WHEN** the user activates `Get model package` and the configured release URL is a valid supported external URL
- **THEN** the operating system opens that URL in a browser, while the application performs no page fetch, package download, or installation

#### Scenario: Release URL is unavailable or malformed
- **WHEN** no release URL is configured, the configured value is malformed or unsupported, or no browser is available
- **THEN** the action presents an unavailable or malformed-URL state and the application makes no navigation or network attempt

### Requirement: Publisher and legal distribution gate

The application SHALL advertise or recommend only packages whose release metadata proves publisher authentication and recorded legal clearance. A self-declared publisher field, checksum, schema validity, or runtime compatibility SHALL NOT by itself count as publisher authentication or legal clearance. Local import SHALL independently require the package integrity, schema, and runtime checks in this capability, and a locally imported package SHALL NOT be advertised or treated as legally cleared merely because those checks pass.

#### Scenario: Unauthenticated or uncleared release is not advertised
- **WHEN** a release entry lacks trusted publisher authentication or recorded legal clearance
- **THEN** the application does not present that package as an approved or advertised package

#### Scenario: Local package validation remains independent
- **WHEN** the user selects a package locally that is not present in an advertised release listing
- **THEN** the application still performs the complete integrity, schema, and runtime verification before activation and does not infer publisher authentication or legal clearance from a passing import

### Requirement: Offline and private execution boundary

The application MUST perform model-package import, verification, status inspection, and inference without an in-app network client and MUST be distributed without the Android `INTERNET` permission. Raw checkpoint files SHALL be treated only as non-executable package data; the runtime MUST open inference inputs only from declared, verified artifacts that match the supported model contract. Imported archives, temporary candidates, and any extracted or streamed model data MUST remain under app-private storage, and manifest paths MUST NOT cause writes or reads outside that storage boundary.

#### Scenario: Hostile archive cannot escape private storage
- **WHEN** a selected archive contains an absolute, parent-traversing, duplicate, undeclared, or otherwise unsafe path
- **THEN** the archive is rejected before activation and no file is created, read, or published outside app-private storage

#### Scenario: Raw checkpoint is selected as an executable model
- **WHEN** a package supplies a raw checkpoint or other undeclared file instead of the verified model artifact required by the runtime contract
- **THEN** the application refuses to execute it, keeps the active package unchanged, and reports a safe validation failure
