# Release documentation bundle

Task 12.6 documentation for `citac-knjiga`. This is a redacted, reproducible
compliance record, not a release approval. It does not implement signed release
artifacts (12.7) or publication gating (12.8).

## Contents

| File | Purpose |
|---|---|
| `sbom.cdx.json` | CycloneDX 1.5 SBOM for the audited Android runtime/test graph plus the source-built eSpeak-NG runtime |
| `dependency-notices.md` | Hash-checked links to the authoritative APK-bundled notices |
| `model-attribution.md` | Voice, dataset, derived-model attribution and recorded legal status |
| `privacy-statement.md` | Offline processing, storage, export, diagnostics and privacy boundaries |
| `threat-model.md` | Threats and mitigations for imported content, packages, audio, exports and devices |
| `benchmark-report.md` | Reproducible benchmark evidence and exact limitations |
| `model-package-compatibility.md` | Versioned package contract, checksums, compatibility checks and gates |
| `bundle-manifest.json` | Input hashes, output inventory and notice references |

## Evidence basis

The bundle is based on the dependency/license audit (`b20a036`), toolchain and
source-closure locks (`273d37b`, `b9c398c`), diagnostics/privacy checks, legal
inventory, parity and recovery evidence, benchmark records, and the capability
matrix. The generated SBOM contains 149 audited Android components over
16 selected runtime/test configurations. Model payloads, generated audio,
document text, secrets and machine-local paths are intentionally absent.

## Reproduction

From the repository root with the locked Android/Python environment available:

```sh
python3 scripts/generate_release_docs.py
python3 scripts/validate_release_docs.py
```

Generation reruns the offline dependency audit before rendering. For a check
against an already generated dependency inventory, use
`python3 scripts/generate_release_docs.py --skip-audit`. The validator compares
all recorded input hashes, notice hashes and SBOM coordinates and fails on drift.
