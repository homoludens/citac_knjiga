#!/usr/bin/env python3
"""Generate the redacted task-12.6 release documentation bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "reports/release-docs"
NOTICE_JSON = ROOT / "app/src/main/assets/notices/dependency-license-inventory.json"
NOTICE_MD = ROOT / "app/src/main/assets/notices/THIRD_PARTY_NOTICES.md"
GENERATOR = Path(__file__).relative_to(ROOT)
OUTPUTS = (
    "README.md",
    "bundle-manifest.json",
    "sbom.cdx.json",
    "dependency-notices.md",
    "model-attribution.md",
    "privacy-statement.md",
    "threat-model.md",
    "benchmark-report.md",
    "model-package-compatibility.md",
)
INPUTS = (
    "app/src/main/assets/notices/dependency-license-inventory.json",
    "app/src/main/assets/notices/THIRD_PARTY_NOTICES.md",
    "gradle/libs.versions.toml",
    "gradle/toolchain.lock.json",
    "gradle/verification-metadata.xml",
    "settings-gradle.lockfile",
    "app/gradle.lockfile",
    "core/gradle.lockfile",
    "tts-onnx/gradle.lockfile",
    "document-epub/gradle.lockfile",
    "playback-export/gradle.lockfile",
    "model-tools/uv.lock",
    "model-tools/native/source-closure-v1.json",
    "model-tools/native/espeak-data-manifest-v1.json",
    "model-tools/native/NOTICE.md",
    "model-tools/preprocessing/preprocessing-contract-v1.json",
    "model-tools/package/model-package-v1.schema.json",
    "model-tools/package/model-package-v1.example.json",
    "model-tools/runtime-pins.md",
    "model-tools/legal-inventory.md",
    "model-tools/parity/fp32-thresholds-v2.json",
    "model-tools/parity/fp32-parity-v2-report.json",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts",
    "app/src/main/java/com/homoludens/citacknjiga/diagnostics/ModelDownloadConfig.kt",
    "fdroid/check-config-v1.json",
    "DEPLOYMENT.md",
    "reports/task-11-4-android-qualification.md",
    "reports/task-11-8-mvp-capability-matrix.md",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def run_audit() -> None:
    subprocess.run(
        [sys.executable, "scripts/audit_dependencies.py"],
        cwd=ROOT,
        check=True,
    )


def maven_component(row: dict[str, object]) -> dict[str, object]:
    coordinate = str(row["coordinate"])
    group = str(row["group"])
    name = str(row["name"])
    version = str(row["version"])
    component: dict[str, object] = {
        "type": "library",
        "bom-ref": f"pkg:maven/{quote(group, safe='.-_')}/{quote(name, safe='.-_')}@{quote(version, safe='.-_+')}",
        "group": group,
        "name": name,
        "version": version,
        "scope": "required" if row["scope"] == "runtime" else "excluded",
        "purl": f"pkg:maven/{quote(group, safe='.-_')}/{quote(name, safe='.-_')}@{quote(version, safe='.-_+')}",
        "licenses": [{"license": {"id": license_id}} for license_id in row["license_ids"]],
        "properties": [
            {"name": "citac-knjiga:scope", "value": str(row["scope"])},
            {"name": "citac-knjiga:used-in-tests", "value": str(bool(row["used_in_tests"])).lower()},
            {"name": "citac-knjiga:audit-status", "value": str(row.get("status", "audited"))},
        ],
    }
    references = [
        {"type": "distribution", "url": url}
        for url in row.get("license_urls", [])
    ]
    if row.get("source_url"):
        references.append({"type": "vcs", "url": row["source_url"]})
    if references:
        component["externalReferences"] = references
    return component


def native_component(closure: dict[str, object]) -> dict[str, object]:
    native = closure["native"]
    repository = str(native["repository"])
    version = str(native["tag"])
    return {
        "type": "library",
        "bom-ref": f"pkg:generic/espeak-ng@{version}",
        "group": "org.espeak-ng",
        "name": "eSpeak-NG",
        "version": version,
        "scope": "required",
        "purl": f"pkg:generic/espeak-ng@{version}",
        "licenses": [{"license": {"id": "GPL-3.0-or-later"}}],
        "externalReferences": [{"type": "vcs", "url": repository}],
        "properties": [
            {"name": "citac-knjiga:source-commit", "value": str(native["commit"])},
            {"name": "citac-knjiga:build", "value": "source-built JNI; no checked-in library"},
        ],
    }


def build_sbom(inventory: dict[str, object], closure: dict[str, object]) -> dict[str, object]:
    components = [maven_component(row) for row in inventory["components"]]
    components.append(native_component(closure))
    components.sort(key=lambda component: str(component["bom-ref"]))
    unsigned = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": "pkg:generic/citac-knjiga@0.1.0",
                "name": "citac-knjiga",
                "version": "0.1.0",
                "licenses": [{"license": {"id": "GPL-3.0-or-later"}}],
            },
            "properties": [
                {"name": "citac-knjiga:dependency-inventory", "value": rel(NOTICE_JSON)},
                {"name": "citac-knjiga:dependency-notices", "value": rel(NOTICE_MD)},
                {"name": "citac-knjiga:non-maven-assets", "value": "documented in bundled notices and source closure"},
                {"name": "citac-knjiga:model-policy", "value": "user-imported and excluded from application SBOM payload"},
            ],
        },
        "components": components,
    }
    identity = hashlib.sha256(
        json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    serial = f"urn:uuid:{identity[:8]}-{identity[8:12]}-{identity[12:16]}-{identity[16:20]}-{identity[20:32]}"
    return {"serialNumber": serial, **unsigned}


def render_documents(
    inventory: dict[str, object],
    sbom: dict[str, object],
    legal: dict[str, object],
    package: dict[str, object],
    preprocessing: dict[str, object],
    closure: dict[str, object],
    data_manifest: dict[str, object],
) -> dict[str, str]:
    components = len(inventory["components"])
    graphs = len(inventory["gradle_graph"]["configurations"])
    package_identity = package["manifest"]["identity"]["value"]
    model_hash = next(item["sha256"] for item in package["artifacts"] if "model" in item["roles"])
    voice_hash = next(item["sha256"] for item in package["artifacts"] if "voice_style" in item["roles"])
    config_hash = next(item["sha256"] for item in package["artifacts"] if "configuration" in item["roles"])
    runtime = package["runtime"]
    native = closure["native"]
    network = closure["network_policy"]
    data_files = data_manifest["files"]
    network_assets = "\n".join(
        f"| `{asset['engine']}` | `{asset['filename']}` | `{asset['size_bytes']}` | `{asset['sha256']}` | <{asset['url']}> |"
        for asset in network["allowed_assets"]
    )
    offline_operations = ", ".join(f"`{operation}`" for operation in network["offline_operations"])
    blocked_reviews = "\n".join(f"- {review}" for review in legal["outstanding_reviews"])
    return {
        "README.md": f"""# Release documentation bundle

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
source-closure/network-policy locks (`273d37b`, `b9c398c`), diagnostics/privacy checks, legal
inventory, parity and recovery evidence, benchmark records, and the capability
matrix. The generated SBOM contains {components} audited Android components over
{graphs} selected runtime/test configurations. Model payloads, generated audio,
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
""",
        "dependency-notices.md": f"""# Dependency notices

The complete dependency notice data is generated once by the existing audited
inventory and bundled in both application release flavors. This bundle links to
that authoritative data rather than copying a second, potentially stale list.

| Record | Repository path | SHA-256 |
|---|---|---|
| Machine-readable inventory | `{rel(NOTICE_JSON)}` | `{sha256(NOTICE_JSON)}` |
| Human-readable notices | `{rel(NOTICE_MD)}` | `{sha256(NOTICE_MD)}` |

The inventory was generated from the locked Gradle runtime/test graph by
`scripts/audit_dependencies.py`. It covers standard and F-Droid release graphs,
module test graphs, native eSpeak-NG provenance, model/dataset provenance, and
test-only dependencies. The SBOM's Maven component set is derived from the same
inventory.

No license text, model payload, generated audio, document content, local cache
path, or legal clearance assertion is duplicated here. License status and
redistribution limitations remain those recorded in the linked inventory and
`model-attribution.md`.
""",
        "model-attribution.md": f"""# Model and voice attribution

This document records provenance and the project's declared treatment. It is
not legal advice, a permission grant, or a clearance certificate.

## Voice and datasets

| Subject | Recorded attribution | License/status |
|---|---|---|
| Serbian Common Voice Style TTS Dataset | Created by Darko Milosevic; spoken by Dragana. Source: <https://huggingface.co/datasets/daremc86/serbian_common_voice> | The source record states CC BY 4.0; attribution and modification notice required. |
| JuzneVesti-SR v1.0 | Peter Rupnik and Nikola Ljubesic; Jozef Stefan Institute / CLARIN.SI. Source: <https://www.clarin.si/repository/xmlui/handle/11356/1679> | The project record declares CC BY-SA 4.0. DUA and underlying broadcast-rights review remain outstanding. |
| Derived Serbian Kokoro/Dragana package | Derived model, ONNX graph, voice/style data and test audio use the above provenance. | The project record treats the derived package as CC BY-SA 4.0, pending the outstanding review. |

Required notices identify both datasets, their authors/speaker or institutions,
source URLs, licenses, and the fact that the model/audio are modified or
derived. Generated audio reproduces characteristics of real people and must not
be used for impersonation or fraud.

## Legal gate

The model package legal status is **blocked** for public distribution. The
application and model package remain separate; the app does not bundle or
download model weights. Outstanding reviews recorded in the legal inventory:

{blocked_reviews}

The declaration that a derived package may be treated as CC BY-SA 4.0 is a
project release treatment, not a verified legal conclusion. Do not publish a
model archive, voice archive, derived audio, or a cleared manifest based only on
this document. The package manifest's fail-closed legal object remains the
authoritative package gate.
""",
        "privacy-statement.md": """# Privacy and offline statement

`citac-knjiga` is designed to process DRM-free EPUB input and generate Serbian
audio locally. In the documented application path, text, tokens, model data and
audio are not uploaded. Both release variants declare `INTERNET` only for the
configured model-asset download boundary and `ACCESS_NETWORK_STATE` only so
WorkManager can wait for connectivity; no analytics or proprietary service is
part of the audited runtime graph.

## Data handling

- The Android document picker supplies a user-selected EPUB or model package;
  the app copies it into private storage before using it. Document import is
  local and does not use the network.
- Direct model acquisition is limited to the two pinned HTTPS GitHub Release
  assets below. It does not accept arbitrary URLs or download documents, code,
  telemetry, or runtime dependencies.
- Generation reads private imported text and installed model artifacts locally;
  it remains offline. Runtime dependency acquisition is a build-time concern
  and remains outside the app runtime.
- The original SAF URI is provenance only. Later EPUB work uses the verified
  private source copy, so a provider disappearing does not require the original
  URI.
- Canonical text, model packages, generated audio, playback state and
  diagnostics are app-private artifacts. Ready files are checksum-verified and
  temporary files are cleaned only by the documented reconciliation policies.
- Export is user initiated to a user-selected SAF destination. The destination
  is outside the app-private boundary and is not treated as private after the
  user exports content.
- Diagnostics and text export use the central redactor. Free-form document
  text, raw exceptions, paths and URIs are not included by default.

## Boundaries and limitations

Offline behavior does not protect data from the Android OS, a compromised
device, root access, backups, or a user-selected external export/player. The
app cannot make claims about other applications after export. A device may
still have network access for unrelated software; the application does not use
that network for document import or narration. The download transport uses only
the pinned allowlist. Privacy checks are
static and test-based, not a privacy certification or a guarantee against future
code changes.
""",
        "threat-model.md": """# Threat model

This is an engineering threat model for the documented MVP boundaries. It is
not a security certification. The main assets are private source text,
canonical narration, model packages, generated audio, playback state, export
destinations and diagnostic records.

| Untrusted input or failure | Threat | Implemented control | Residual limitation |
|---|---|---|---|
| EPUB archive selected through SAF | Zip Slip, absolute paths, duplicate/encrypted entries, oversized expansion or compression bombs | Copy to private temporary storage; canonical containment, entry/size/ratio limits, encryption checks and no extraction before validation | Bounded validation cannot prove acceptance of every malformed EPUB. |
| EPUB XML/HTML content | DTD/entity expansion and external resource access | Reject DTD/entities and external URI references; bounded XML inspection and resolver; no network fetch | Parser implementation or future format support still needs review. |
| SAF source provider or disappearing URI | Provider returns partial/corrupt data or later becomes unavailable | Stream to private storage, fingerprint and validate before publication; retain private source copy; never turn a URI into a filesystem path | The Android OS and provider remain outside the app trust boundary. |
| User-imported model package | Corrupt, incomplete, incompatible or tampered package replaces a valid package | Temporary copy, manifest/schema checks, declared size/SHA-256 checks, compatibility checks and rollback to the last valid package | Model ZIP resource-exhaustion limits are not a separately qualified security boundary; do not import untrusted packages as cleared. |
| Model inference/output | NaN, infinity, silence, clipping, wrong duration or corrupt audio becomes ready | Output validation, checksum, atomic publication, Room READY checkpoint and retry/failure state | A passing numerical contract does not establish speech quality or harmless model behavior. |
| Generated audio and playback | Missing, stale or corrupt files cause unsafe queue behavior or data loss | Reconcile provenance and checksums; queue only verified private audio; stop/skip and regeneration routes | External players and vendor media behavior are not fully qualified. |
| SAF export destination | Partial writes, collisions, low capacity or provider loss damage the project | Preflight capacity checks, `.incomplete` writes, read-back verification, safe finalization, collision handling and persisted checkpoints | Real provider/device loss and two-player playback evidence remain incomplete. |
| Process death, reboot, update or storage failure | Completed work is regenerated or a partial file is marked ready | Room state machine, bounded segment checkpoints, synced temporary writes, atomic publication and startup reconciliation | Physical force-stop/reboot/update and the required Android-version matrix remain unqualified. |
| Diagnostics or logs | Document text, URI, path, exception or secret leaks | Safe-token messages, constrained IDs/hashes/numbers and redacted export; no free-form payload logging | OS logs and user/device compromise are outside this component's control. |
| Network or remote services | Accidental upload, arbitrary fetch, or routine tracking | `INTERNET` is limited by the pinned GitHub Release asset policy; `ACCESS_NETWORK_STATE` is used only by WorkManager connectivity constraints; cleartext, routine network clients, analytics, and proprietary services are rejected; document import and generation are local | This is not a firewall: other installed software and user export destinations may use network access. |

Release interpretation: unresolved limitations are recorded, not silently
accepted. The legal model gate, missing external-player evidence and incomplete
Android qualification remain blockers for a release-candidate decision.
""",
        "benchmark-report.md": """# Benchmark report

These are recorded measurements, not acceptance gates. They contain device,
runtime and numeric observations only; no document text, generated audio or
local report path is included.

## Sustained production-path observation

Run date: 2026-08-28. Device: Xiaomi M2012K11AG / alioth, Android 13 API 33,
native ARM64. Runtime: ONNX Runtime Android 1.29.0, CPU sequential, threads
1/1. Workload: 203 inference calls and 902.45 generated audio seconds.

| Measurement | Result |
|---|---:|
| Model load | 2,964 ms |
| Workload wall time | 1,594.649 s |
| Real-time factor | 1.767 |
| Peak process PSS | 908,320,768 bytes |
| Process CPU | 114.108% average; 206.336% sampled peak |
| Battery | 52% to 50% |
| Battery temperature | 35.7 C to 37.0 C |
| Android thermal status | 0 throughout; throttling not observed |

## Runtime comparison

Each run targeted 15 seconds and generated 18.875 audio seconds on the same
Poco F3 ARM64/API 33 device. Peak memory is sampled total PSS, not portable
peak RSS.

| Configuration | Real-time factor | Peak process PSS |
|---|---:|---:|
| CPU, threads 1/1 | 1.379 | 869,524,480 bytes |
| CPU, threads 2/1 | 0.976 | 910,154,752 bytes |
| CPU, threads 4/1 | 0.603 | 895,176,704 bytes |
| XNNPACK, provider threads 1 | 1.722 | 901,241,856 bytes |
| XNNPACK, provider threads 2 | 1.680 | 909,795,328 bytes |
| XNNPACK, provider threads 4 | 1.698 | 886,729,728 bytes |

## AAC/M4A codec observation

Run date: 2026-08-30. Device: API 35 Google x86_64 emulator. Codec:
`c2.android.aac.encoder`. Fixture: four seconds of deterministic synthetic
Serbian-consonant windows at 24 kHz mono PCM16.

| Requested bitrate | M4A size | Encoded duration | Boundary gap | Boundary trim | Max drift |
|---:|---:|---:|---:|---:|---:|
| 64 kbps | 35,123 B | 3.968 s | 0 us | 245,336 us | 30,667 us |
| 80 kbps | 42,970 B | 3.968 s | 0 us | 245,336 us | 30,667 us |
| 96 kbps | 50,855 B | 3.968 s | 0 us | 245,336 us | 30,667 us |

## Exact limitations

- RTF and memory have no acceptance threshold and do not gate implementation.
- Total PSS is not portable peak RSS; process CPU lacks vendor scheduler detail;
  battery temperature is not SoC/skin temperature; thermal status lacks vendor
  zones; battery percentage is a rounded boundary sample.
- The sustained run is historical Poco F3 evidence. The current available
  emulator is not substituted for the required device qualification.
- The AAC fixture is synthetic. It cannot establish natural Serbian
  intelligibility, consonant quality, or a bitrate quality winner. Manual
  natural-speech A/B listening and Poco F3 ARM64 AAC qualification are pending.
- Task 11.4 remains blocked because Android 11, Android 16 and physical Poco
  F3 vendor battery-management runs are unavailable. Task 10.8 remains blocked
  because two external Android audio players were not available.
- A fresh full desktop parity rerun timed out; the committed parity report and
  passing validators remain the evidence. No new result is inferred here.

Reproduction wrappers are `scripts/run_android_benchmark.sh`,
`scripts/run_android_runtime_matrix.sh` and
`scripts/run_android_aac_benchmark.sh`. Keep model packages, reports and audio
outside the repository.
""",
        "model-package-compatibility.md": f"""# Model-package compatibility contract

The application accepts only the versioned `serbian-model-package` schema v1.
This contract describes the checks performed before a package becomes active;
schema validity is not legal clearance.

## Identity and payload contract

| Field | Required value |
|---|---|
| Schema | `serbian-model-package:1` |
| Package identity | SHA-256 of package ID, package version and sorted artifact path/hash pairs; manifest is excluded from its own checksum |
| Model | Kokoro-82M family, ONNX, AI ONNX opset 18, IR 8 |
| Inputs | `input_ids` int64 `[1, seq_len]`, `ref_s` float32 `[1, 256]`, positive scalar `speed` |
| Outputs | `waveform` float32 mono 24,000 Hz and `pred_dur` int64 with matching sequence length |
| Input limits | 507 operational phoneme symbols; 510 hard limit; vocabulary size 178 |
| Voice | `dragana-sr`, float32 style table `[510, 1, 256]`; row `min(symbol_count, 509)` |
| Preprocessing | `kokoro-sr-ca5590d9`, contract v1, Serbian locale, exact eSpeak-NG IPA mode 3 command |
| Android runtime | ONNX Runtime Android 1.29.0, API 30+, `arm64-v8a`, CPU provider, threads 1/1 |
| Parity gate | `fp32-parity-v2`, all 26 required vectors and every declared metric pass |

## Direct model-download policy

The release and F-Droid variants declare `android.permission.INTERNET` for direct
model acquisition and `android.permission.ACCESS_NETWORK_STATE` for the
WorkManager connected-network constraint. The latter does not transfer data.
Cleartext traffic and arbitrary URLs are rejected. The allowlist is immutable
application configuration:

| Engine | Filename | Expected bytes | Outer SHA-256 | HTTPS asset |
|---|---|---:|---|---|
{network_assets}

Document import, generation, and runtime dependency acquisition remain offline.
The download transport uses only the allowlist above and preserves the existing
package on failure.

## Recorded checksums and versions

These are identities, not embedded payloads. The model archive, model bytes,
voice bytes and generated audio are not in this repository.

| Item | Version/identity | SHA-256 or status |
|---|---|---|
| Model graph | `epoch-005-onnx-fp32` | `{model_hash}` |
| Dragana voice/style | `dragana-epoch-005` | `{voice_hash}` |
| Configuration/vocabulary | `config-5abb01e2` | `{config_hash}` |
| Preprocessing contract | `serbian-preprocessing-contract:1` | `{preprocessing["identity"]["value"]}` |
| `kokoro` runtime | `semidark/kokoro` revision `{package["model"]["source"]["runtime_revision"]}` | source revision pinned |
| `kokoro_sr` | revision `{preprocessing["implementation"]["source_revision"]}` | source files hash-pinned |
| eSpeak-NG | `{native["tag"]}` / commit `{native["commit"]}` | source-built; no checked-in JNI library |
| ONNX Runtime AAR | `{runtime["coordinate"]}` | `{runtime["runtime_artifact"]["sha256"]}` |
| Qualified local package archive | `kokoro-serbian-dragana@1.0.0` | `58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`; external and uncommitted observation |
| Model-package identity fixture | `{package_identity}` | declaration-only blocked fixture |

The seven-file eSpeak-NG data closure is hash-pinned by
`model-tools/native/espeak-data-manifest-v1.json` ({len(data_files)} files) and
its source/build closure by `model-tools/native/source-closure-v1.json`.
The golden corpus identity is `{preprocessing["compatibility"]["validation"]["corpus_sha256"]}`;
the active parity declaration is `fp32-parity-v2`.

## Import and qualification checks

1. Copy the selected archive to private temporary storage.
2. Parse the strict manifest and reject unknown/new core schema fields.
3. Require the model, voice, configuration, vocabulary and test-vector roles.
4. Verify package-relative paths, declared sizes and SHA-256 values.
5. Check runtime, ABI, preprocessing, sample-rate, tensor and parity versions.
6. Publish atomically only after validation; retain the previous valid package
   on failure and record an actionable failure code.

The desktop v2 report records 26/26 parity vectors with worst MAE
`0.0044169974`, maximum error `0.1190068983`, and minimum STFT cosine
`0.9992545506`. Historical Poco F3 ARM64 parity also passed 26/26, but the
current app intentionally has no staged package. Android 11/16 matrix coverage,
external-player coverage, and legal model distribution remain separate gates.

## Distribution status

The package legal status is **blocked**. Do not infer public redistribution from
checksums, parity, or schema validation. The model/voice/dataset attribution
and outstanding legal reviews are in `model-attribution.md` and the linked
legal inventory.
""",
    }


def build_manifest() -> dict[str, object]:
    source_paths = [ROOT / path for path in INPUTS]
    source_paths.append(ROOT / GENERATOR)
    return {
        "schema": "citac-knjiga-release-document-bundle",
        "version": 1,
        "bundle_id": "task-12-6",
        "generated_by": {"path": GENERATOR.as_posix(), "sha256": sha256(ROOT / GENERATOR)},
        "inputs": [{"path": rel(path), "sha256": sha256(path)} for path in source_paths],
        "outputs": list(OUTPUTS),
        "notice_references": {
            "inventory": {"path": rel(NOTICE_JSON), "sha256": sha256(NOTICE_JSON)},
            "markdown": {"path": rel(NOTICE_MD), "sha256": sha256(NOTICE_MD)},
        },
        "redaction": {
            "document_text": "excluded",
            "sensitive_uris": "excluded",
            "local_absolute_paths": "excluded",
            "model_and_audio_payloads": "excluded",
            "secrets": "excluded",
        },
    }


def write_bundle() -> None:
    inventory = read_json(NOTICE_JSON)
    package = read_json(ROOT / "model-tools/package/model-package-v1.example.json")
    closure = read_json(ROOT / "model-tools/native/source-closure-v1.json")
    data_manifest = read_json(ROOT / "model-tools/native/espeak-data-manifest-v1.json")
    preprocessing = read_json(ROOT / "model-tools/preprocessing/preprocessing-contract-v1.json")
    sbom = build_sbom(inventory, closure)
    # Legal reviews are intentionally read from the blocked package fixture so
    # this generator cannot turn prose in a report into release clearance.
    documents = render_documents(inventory, sbom, package["legal"], package, preprocessing, closure, data_manifest)
    BUNDLE.mkdir(parents=True, exist_ok=True)
    for name, content in documents.items():
        (BUNDLE / name).write_text(content, encoding="utf-8")
    manifest = build_manifest()
    (BUNDLE / "sbom.cdx.json").write_text(json.dumps(sbom, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (BUNDLE / "bundle-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-audit", action="store_true", help="use the existing audited notice inventory")
    args = parser.parse_args()
    if not args.skip_audit:
        run_audit()
    write_bundle()
    print(f"generated {len(OUTPUTS)} release documentation files in {BUNDLE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
