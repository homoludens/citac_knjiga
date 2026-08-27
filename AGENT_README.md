# citac_knjiga — Offline Serbian EPUB-to-Audiobook Android App

Turns DRM-free ebooks into persistent, locally generated audiobooks using the
custom Dragana Serbian Kokoro voice. Offline-first, open source, F-Droid
target.

## Status

- OpenSpec change `build-serbian-audiobook-mvp` is in progress:
  `openspec/changes/build-serbian-audiobook-mvp/` (proposal, design, 6 specs,
  12 task phases).
- Phase 2 model export/parity is active: tasks 2.4-2.7 froze and exercised the
  FP32 parity contract, desktop ONNX validation, and the bounded Sherpa
  experiment; task 2.8 selects direct ONNX Runtime Android `1.29.0` as the
  implementation target. Android parity and device qualification remain later
  tasks.
- Task 3.1 defines the strict v1 model-package manifest, blocked legal fixture,
  SHA-256 identity, and declaration validator under `model-tools/package/`.
  Packaging/import and Android qualification remain later tasks.

## Repository layout

| Path | Purpose |
|---|---|
| `citac_knjiga.md` | Original project brief (source of truth for intent) |
| `openspec/` | Spec-driven change artifacts (proposal / specs / design / tasks) |
| `kokoro_sr_dragana_voice/` | Known-good Dragana checkpoint bundle (epoch-005), LFS-tracked |
| `python_voice_test/` | Earlier self-contained Dragana inference bundle (epoch_2nd_00002) |
| `speak_2.py` | Ad-hoc CPU inference test script (points at training-repo paths) |
| `model-tools/` | Desktop model tooling: runtime pins, env lock, reference captures, export wrapper, package schema/validator (Phase 1–3) |
| (later) | Android app modules (`app`, `core`, `tts-onnx`, `document-epub`, `playback-export`) |

## Key technical facts

- Model: Kokoro-82M fine-tuned for Serbian (Južne vesti base) then on the
  Dragana single-speaker dataset. 24 kHz mono output.
- Runtime: pinned Kokoro fork `semidark/kokoro@b96fef95` (NOT PyPI 0.9.4 —
  weight-norm difference makes PyPI produce noise).
- Phonemizer: `kokoro_sr.phonemes.phonemize_serbian` — eSpeak-NG `--ipa=3 -v sr`
  plus symbol normalization/audit against the Kokoro v1 vocabulary.
- Input limit: 507 phoneme symbols per model call.
- Voice tensor shape: `[510, 1, 256]`; sampled at `min(len(ipa), 509)`.
- Export wrapper (task 2.1): `model-tools/export/wrapper.py` exposes the
  deterministic tensor boundary (token IDs + selected style row + speed →
  24 kHz float32 PCM + pred_dur) that task 2.2 exports to ONNX. See
  `model-tools/export/README.md` for the interface contract.
- Desktop parity (task 2.5): run
  `model-tools/.venv/bin/python model-tools/scripts/run_parity.py` to compare
  the PyTorch CustomSTFT baseline with ONNX Runtime CPU over all committed
  vectors. Reports are written to
  `model-tools/parity/fp32-parity-report.json` and
  `model-tools/parity/fp32-parity-report.txt`.
- Android runtime decision (task 2.8): direct
  `com.microsoft.onnxruntime:onnxruntime-android:1.29.0` is selected; CPU is
  the acceptance baseline and XNNPACK is a separately measured variant. See
  `model-tools/android-runtime-decision.md`; this does not claim Android or
  device qualification.

## Conventions

- Commit style: `type(scope): description` (see `AGENTS.md`).
- Commit after every task; a fresh-context agent per task.
- Deployment/environment steps live in `DEPLOYMENT.md`.
