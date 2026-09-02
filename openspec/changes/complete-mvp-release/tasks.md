## PDF Import Reliability

- [ ] 1.1 Reproduce the Poco F3 PDF picker failure with a locally stored text PDF, capture the selected URI/provider class and redacted diagnostic, and identify whether failure occurs during open, copy, format validation, or PdfBox inspection.
- [ ] 1.2 Fix the smallest failing PDF source/import path while preserving private one-time staging, cancellation cleanup, provenance, and fail-closed behavior; add focused unit and Android regression coverage for success and the observed failure.
- [ ] 1.3 Improve localized PDF diagnostics so source-unavailable, copy/source-changed, unsupported, protected, image-only, timeout, and acceptance failures are distinguishable without leaking paths, URIs, text, or exceptions.
- [ ] 1.4 Run the API 33 ARM64 real-device PDF regression and record the result in `DEPLOYMENT.md` and `AGENT_README.md`.

## VITS Qualification

- [ ] 2.1 Complete original task `enable-sherpa-vits-runtime` 5.1: run API 33 `arm64-v8a` offline VITS generation and an equivalent API 33 target, recording correctness, timing, memory, interruption, recovery, and no-network evidence.
- [ ] 2.2 Update the VITS qualification report and release documentation so the experimental/qualified status, package identity, and remaining deviations are explicit.

## Portable Export

- [ ] 3.1 Complete original task `build-serbian-audiobook-mvp` 10.8: export a portable chapter and verify correct playback in at least two external Android audio players.
- [ ] 3.2 Record output hashes, codec/container, duration, player identities/versions, ordering, and any portability limitations without including private text or credentials.

## Device And Capability Verification

- [ ] 4.1 Complete original task `build-serbian-audiobook-mvp` 11.4: run sustained generation-plus-playback tests on Android 11, a current Android release, Android 16, and the Poco F3 vendor battery-management configuration, or record each unavailable target as unresolved.
- [ ] 4.2 Complete original task `build-serbian-audiobook-mvp` 11.8: verify every capability scenario and record unresolved deviations before declaring the release candidate.

## Signed Release And Publication

- [ ] 5.1 Complete original task `build-serbian-audiobook-mvp` 12.7: build and verify signed standard and F-Droid artifacts with an external keystore, including package, signature, payload, dependency, source-closure, notices, and checksum checks.
- [ ] 5.2 Complete original task `build-serbian-audiobook-mvp` 12.8: run the final publication gate after legal, parity, recovery, export, OpenSpec, and device-review evidence is complete; publish only if every hard gate passes.
