# Deployment steps and environment setup for citac_knjiga.
# Keep the known-good steps here so any machine can reproduce the pipeline.

## Desktop Python inference environment (Kokoro Serbian / Dragana)

The reference CPU inference path uses the pinned Kokoro runtime (see
`model-tools/runtime-pins.md`) with Python 3.11 and eSpeak-NG.

- Python 3.11 via uv (managed CPython 3.11.x).
- `espeak-ng` system package (phonenumber phonemizer, `-v sr`).
- Virtualenv created with uv; dependencies locked in `model-tools/uv.lock`.
- Reproduce: see `model-tools/README.md` (populated in task 1.2).

## Git LFS

Model weights, voice tensors, and reference audio are Git-LFS tracked via
`.gitattributes`. Install: `git lfs install` (already done on this machine).
Verify a clone actually fetched weights with `git lfs ls-files`.