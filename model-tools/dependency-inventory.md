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
| eSpeak-NG engine + Serbian data | 1.52.0 / data closure TBD | GPL-3.0-or-later (data notices TBD) | **blocked** in app | Task 3.6 selects a native arm64/JNI candidate for exact parity; current project policy forbids linking GPL/AGPL components into the Android app. See `model-tools/phonemization-decision.md`. |
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
| Dragana checkpoint `kokoro_dragana_sr.pth` | `4e6d11053886` | Derivative: CC BY 4.0 (Dragana data) + CC BY-SA 4.0 (JV base) | **blocked** | Release gate: legal-inventory.md §1.8 |
| Voice tensor `sr_dragana.pt` | `0c16ae704368` | Same as checkpoint | **blocked** | |
| `config.json` | `5abb01e2403b` | Project code license | yes | |

## 5. Datasets (never bundled with app; referenced for provenance)

| Dataset | Source | License | Redistributable? | Notes |
|---|---|---|---|---|
| Serbian Common Voice Style TTS (Dragana) | `daremc86/serbian_common_voice` | CC BY 4.0 | yes (attribution) | See legal-inventory.md §1.5 |
| Južne vesti corpus v1.0 | handle `11356/1679` (CLARIN.SI) | CC BY-SA 4.0 | **pending** | DUA terms unverified; see §1.6 |

## 6. Fonts & test fixtures

| Component | Version/Source | License | Redistributable? | Notes |
|---|---|---|---|---|
| UI font (Serbian diacritics) | TBD | — | — | Must cover č ć š ž đ + Cyrillic |
| EPUB test fixtures (Phase 7.1) | To be assembled | Must be self-authored or CC0 | yes | No third-party text without license |
| Golden text vectors | Self-authored | Project code license | yes | |

## Rules

1. Any `GPL`/`AGPL` component MUST NOT be linked into the Android app
   (F-Droid + app license conflict). eSpeak-NG remains desktop-reference only
   until the task-3.6 native strategy is legally compatible and reproducibly
   qualified.
2. Any model weight row stays `blocked` until `legal-inventory.md` §1.8 gate
   passes; the packager (task 3.2) MUST refuse to include blocked rows.
3. Every `pending` row must be resolved before the MVP release candidate
   (task 11.7).
