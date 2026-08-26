#!/usr/bin/env python3
"""Verify randomness control for the Dragana inference path (task 1.3).

The network (BERT/duration/decoder) runs in eval() with no dropout, but the
HnNSF source in the vocoder (istftnet.SineSource) adds:
  - random initial phase:  torch.rand(...)   (istftnet.py:150)
  - additive Gaussian noise: torch.randn_like(...) (istftnet.py:205, 253)
so the SAME input produces different waveforms across runs.

This script proves both facts and identifies the control:
  A) two runs, no seed  -> outputs differ (stochastic)
  B) two runs, same seed before each -> outputs bit-identical (deterministic)
  C) two runs, different seeds -> outputs differ

The parity runner (task 2.5) MUST seed torch identically for reference and
candidate, and parity metrics are statistical (see model-io.md).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

TRAINING_REPO = Path("/home/homoludens/projekti/kokoro_tts_srpski_2")
sys.path.insert(0, str(TRAINING_REPO / "workspace/kokoro-serbian/runtime/upstream/kokoro-training"))
BUNDLE = Path("/home/homoludens/projekti/citac_knjiga/kokoro_sr_dragana_voice")

import numpy as np  # noqa: E402
import torch  # noqa: E402
from kokoro import KModel  # noqa: E402

TEXT = "Dobar dan, ovo je glas Dragane. Ona čita knjige svakog jutra."
sys.path.insert(0, str(TRAINING_REPO / "src"))
from kokoro_sr.phonemes import phonemize_serbian  # noqa: E402

model = KModel(
    repo_id="hexgrad/Kokoro-82M",
    config=str(BUNDLE / "config.json"),
    model=str(BUNDLE / "kokoro_dragana_sr.pth"),
).to("cpu").eval()
voice = torch.load(BUNDLE / "sr_dragana.pt", map_location="cpu", weights_only=True)
ipa = phonemize_serbian(TEXT)
row = voice[min(len(ipa), 509)]


def run(seed: int | None) -> np.ndarray:
    if seed is not None:
        torch.manual_seed(seed)
    with torch.inference_mode():
        audio = model(ipa, row, speed=1.0)
    return audio.detach().cpu().numpy()


a = run(None)
b = run(None)
c1 = run(1234)
c2 = run(1234)
d = run(9999)


def report(name: str, x: np.ndarray, y: np.ndarray) -> dict:
    same_shape = x.shape == y.shape
    diff = float(np.max(np.abs(x - y))) if same_shape else float("nan")
    return {
        "name": name,
        "same_shape": same_shape,
        "max_abs_diff": diff,
        "bit_identical": bool(same_shape and np.array_equal(x, y)),
        "cosine_similarity": float((x @ y) / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-12)),
        "shape": list(x.shape),
    }


out = {
    "text": TEXT,
    "A_unseeded_vs_unseeded": report("A", a, b),
    "B_seed1234_vs_seed1234": report("B", c1, c2),
    "C_seed1234_vs_seed9999": report("C", c1, d),
}
print(json.dumps(out, indent=2))

# Save a seeded reference (the canonical one used by task 1.4 vectors).
import soundfile as sf  # noqa: E402

out_path = Path("/home/homoludens/projekti/citac_knjiga/model-tools/reference/smoke-test-seeded-1234.wav")
sf.write(out_path, c1, 24000, subtype="PCM_16")
print("seeded reference wav:", out_path)