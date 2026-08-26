# Deployment & Environment — citac_knjiga

Known-good steps to reproduce the desktop CPU inference path. Keep this file
current as the environment changes.

## Desktop model tooling (`model-tools/`)

The reference CPU inference path uses the **pinned Kokoro runtime** (see
`model-tools/runtime-pins.md`) with Python 3.11 and espeak-ng.

### One-time setup

1. **System prerequisites**
   - `git-lfs` (weights are LFS-tracked): `git lfs install`
   - `espeak-ng` (Serbian phonemizer, `-v sr`): present at `/usr/bin/espeak-ng`
     v1.52.0, data at `/usr/share/espeak-ng-data`.
   - `uv` (Python package/env manager).

2. **Environment (locked)**
   ```bash
   cd model-tools
   uv sync --python 3.11        # creates .venv, resolves + locks (uv.lock)
   ```
   - `uv.lock` (310 packages) pins the exact versions, including
     `kokoro @ git+https://github.com/semidark/kokoro.git@b96fef95...` and
     `torch==2.13.0` (the CUDA build is fine for CPU inference; the reference
     samples are CPU-generated).
   - **Do not replace the pinned kokoro with PyPI `kokoro==0.9.4`** — the
     weight-norm implementation differs and produces noise. The smoke test
     asserts the weight-norm guard.

3. **The pinned `kokoro` package and `kokoro_sr` phonemizer live in the
   training repo**, not in this venv. `scripts/smoke_inference.py` inserts
   them on `sys.path` at the exact pinned paths:
   - `kokoro` package:
     `/home/homoludens/projekti/kokoro_tts_srpski_2/workspace/kokoro-serbian/runtime/upstream/kokoro-training/`
   - `kokoro_sr` source: `/home/homoludens/projekti/kokoro_tts_srpski_2/src/`
   - If those paths move, update the two `Path(...)` constants in
     `scripts/smoke_inference.py` (and `speak_2.py`).

### Verify (smoke test)

```bash
cd model-tools
.venv/bin/python scripts/smoke_inference.py
```

Expected (2026-08-26, this desktop): `ok: true`, model/voice SHA-256 match the
bundle, voice shape `[510,1,256]`, finite float32 audio, peak < 1.0, 24 kHz.
Writes `model-tools/reference/smoke-test.wav` (LFS-tracked).

### Reference artifacts

- `model-tools/reference/` — LFS-tracked reference captures (smoke WAV now;
  the full task 1.4 golden input/intermediate/PCM set is added next).
- `model-tools/runtime-pins.md` — immutable runtime/source/bundle identity.
- `model-tools/legal-inventory.md` — data/weight licensing + release gate.
- `model-tools/dependency-inventory.md` — dependency/license inventory template.

## Voice bundle

`kokoro_sr_dragana_voice/` is the current known-good epoch-005 Dragana bundle
(LFS-tracked `.pth`/`.pt`). `python_voice_test/` is an older epoch_2nd_00002
export retained for provenance. See `runtime-pins.md` §4.