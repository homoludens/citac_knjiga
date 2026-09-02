# Dependency & License Inventory (task 1.7)

Template for auditing every component shipped with the application, model
package, and desktop tooling. Fill one row per component; re-verify at each
release (task 11.7) and keep this file in the repo root of each affected
project (Android app, model package, model-tools).

Status values: `ok` (compatible + documented) · `pending` (needs review) ·
`blocked` (incompatible / uncleared, release gate applies).

## 1. Application code (Android)

| Component | Version | License | Redistributable? | Notes |
|---|---|---|---|---|
| App (citac_knjiga) | 0.1 | (choose: e.g. Apache-2.0) | yes | This project |

_Fill as modules are added (Room, DataStore, Media3, WorkManager, Readium,
ONNX runtime, Compose, etc.)._

## 2. Native / system libraries (linked or required on device)

| Component | Version | License | Redistributable? | Notes |
|---|---|---|---|---|
| ONNX Runtime (Android) | 1.29.0 | MIT | yes | Selected task 2.8: exact Maven Central AAR; SHA-256 `e97540ca78fe36f6fe2013f82843414fb843b6c7681fb04644cba5e1406662dd`; CPU baseline with bounded XNNPACK experiment; arm64-v8a filter; not device-qualified |
| PdfBox-Android | 2.0.27.0 | Apache-2.0 | yes | Selected for production PDF extraction after API 33 `arm64-v8a` and API 35 `x86_64` qualification; artifact SHA-256 `30277f879cfd571db2a137582c95516a0d4ea6778e945519bc58ca93d57d88c7`; see `document-pdf/pdfbox-source-closure.json` |
| Bouncy Castle (`bcprov`, `bcpkix`, `bcutil` jdk15to18) | 1.72 | MIT-like | yes | PdfBox-Android runtime transitive closure; exact artifact hashes and license URL are checked in under `document-pdf/` |
| eSpeak-NG engine + Serbian data | 1.52.0 / checked-in closure | GPL-3.0-or-later (file-level data audit required) | yes, with GPL source/notices | Task 3.6's resolution accepts the native arm64/JNI implementation in the app. Source, build provenance, notices, and data audit remain release obligations; see `model-tools/phonemization-decision.md`. |
| Android NDK components | TBD | BSD-3-Clause | yes | Only if retained |

## 3. Model tooling (desktop, `model-tools/`)

| Component | Version | License | Redistributable? | Notes |
|---|---|---|---|---|
| Python | 3.11.x (uv-managed) | PSF-2.0 | yes | Desktop only |
| kokoro (pinned fork) | semidark/kokoro@b96fef95 | Apache-2.0 (hexgrad lineage) | yes | Inference runtime, see runtime-pins.md |
| misaki[en] | (locked, see uv.lock) | MIT/Apache-2.0 | yes | Pulled via kokoro; used for en G2P, not sr |
| torch | 2.13.0 (uv.lock) | BSD-3-Clause | yes | CPU only for reference path |
| soundfile | 0.14.0 | LGPL-3.0 / BSD (libsndfile) | yes | Check libsndfile linkage mode |
| transformers | 5.16.1 (uv.lock) | Apache-2.0 | yes | Pulled via kokoro |
| eSpeak-NG | 1.52.0 | GPL-3.0 | desktop only | Phonemizer subprocess |

## 4. Model files (distributed separately from app — legal gate)

| Component | SHA-256 (first 12) | License | Redistributable? | Notes |
|---|---|---|---|---|
| Kokoro-82M base weights (hexgrad) | (record at package time) | Apache-2.0 (per HF card) | **pending** | Verify base license text at packaging |
| Dragana checkpoint `kokoro_dragana_sr.pth` | `4e6d11053886` | Derived package: CC BY-SA 4.0; Dragana source CC BY 4.0 | public package path | Required attribution/modification notice; exact final hash generated locally |
| Voice tensor `sr_dragana.pt` | `0c16ae704368` | Derived package: CC BY-SA 4.0; Dragana source CC BY 4.0 | public package path | Required attribution/modification notice; exact final hash generated locally |
| `config.json` | `5abb01e2403b` | Project code license | yes | |

## 5. Datasets (never bundled with app; referenced for provenance)

| Dataset | Source | License | Redistributable? | Notes |
|---|---|---|---|---|
| Serbian Common Voice Style TTS (Dragana) | https://huggingface.co/datasets/daremc86/serbian_common_voice | CC BY 4.0 | yes (attribution) | See legal-inventory.md §1.5 |
| JuzneVesti-SR corpus v1.0 | https://www.clarin.si/repository/xmlui/handle/11356/1679 | CC BY-SA 4.0 | yes (attribution/share-alike) | Public derived package is treated as CC BY-SA 4.0; see §1.6 |

## 6. Fonts & test fixtures

| Component | Version/Source | License | Redistributable? | Notes |
|---|---|---|---|---|
| UI font (Serbian diacritics) | TBD | — | — | Must cover č ć š ž đ + Cyrillic |
| EPUB test fixtures (Phase 7.1) | To be assembled | Must be self-authored or CC0 | yes | No third-party text without license |
| Golden text vectors | Self-authored | Project code license | yes | |

## Rules

1. GPL/AGPL components require their corresponding source, notices, license
   text, build provenance, and any applicable data notices. The accepted native
   eSpeak-NG implementation is linked into the Android app; Android/device and
   release reproducibility gates remain separate qualification work.
2. Public model-package rows are generated only through the cleared-manifest
   path, with exact local payload hashes; the packager MUST refuse blocked rows.
3. Every `pending` row must be resolved before the MVP release candidate
   (task 11.7).
