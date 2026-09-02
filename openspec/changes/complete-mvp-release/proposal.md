## Why

The MVP has working Kokoro, VITS, EPUB import, and qualified PdfBox parser code,
but release evidence is incomplete and PDF selection currently fails for at least
one real device/provider path. Consolidating the remaining qualification,
portability, release, and import-reliability work gives the project one clear
implementation and verification queue.

## What Changes

- Diagnose and fix PDF SAF selection/staging failures, with actionable localized diagnostics and real-device coverage.
- Complete API 33 ARM64 VITS offline qualification and record correctness, performance, interruption, recovery, and no-network evidence.
- Demonstrate chapter exports in at least two external Android audio players.
- Run the sustained generation/playback device matrix and record deviations.
- Verify every capability scenario and produce a release-candidate deviation register.
- Build and verify signed standard and F-Droid release artifacts using an external production keystore.
- Run the final publication gate only after legal, parity, recovery, export, OpenSpec, and device-review gates pass.

## Capabilities

### New Capabilities

- `pdf-import-reliability`: Reliable local PDF picker, SAF staging, diagnostics, and real-device import behavior.
- `vits-android-qualification`: Final API 33 ARM64 VITS qualification evidence and release status.
- `portable-audio-export`: External-player-compatible chapter export and portability evidence.
- `device-matrix-verification`: Sustained generation/playback validation across required Android targets and vendor behavior.
- `release-publication`: Capability audit, signed artifacts, legal/release gates, and publication readiness.

### Modified Capabilities

None. Existing change-local specifications are being consolidated into these new capabilities before the completed and superseded changes are archived.

## Impact

Affected areas include `document-pdf` SAF staging and diagnostics, Sherpa VITS
qualification tooling and reports, generation/playback/export instrumentation,
release signing and verification scripts, legal/release documentation, and the
OpenSpec change archive. No model weights or signing secrets belong in the
repository.
