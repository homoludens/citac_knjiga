#!/usr/bin/env python3
"""Probe the maximum-input boundary of the pinned Dragana model (task 1.3).

Replicates exactly what KModel.forward does for the token/voice/limit logic,
then runs a few real inferences at boundary lengths to *verify* the rule:

  - Hard BERT-context limit:  len(input_ids) + 2 <= context_length(=512)
  - Voice style table:        shape [510,1,256], row index = min(len(ipa), 509)
  - Reference operational cap used by speak.py: 507

For each length L we (a) check the assert that forward() would apply and
(b) run a real CPU inference on L repeated 'e' phonemes to confirm the voice
row resolves and finite audio is produced.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

TRAINING_REPO = Path("/home/homoludens/projekti/kokoro_tts_srpski_2")
KOKORO_RUNTIME = TRAINING_REPO / "workspace/kokoro-serbian/runtime/upstream/kokoro-training"
BUNDLE = Path("/home/homoludens/projekti/citac_knjiga/kokoro_sr_dragana_voice")
sys.path.insert(0, str(KOKORO_RUNTIME))

import numpy as np  # noqa: E402
import torch  # noqa: E402
from kokoro import KModel  # noqa: E402

model = KModel(
    repo_id="hexgrad/Kokoro-82M",
    config=str(BUNDLE / "config.json"),
    model=str(BUNDLE / "kokoro_dragana_sr.pth"),
).to("cpu").eval()
voice = torch.load(BUNDLE / "sr_dragana.pt", map_location="cpu", weights_only=True)
context_length = model.bert.config.max_position_embeddings

print(f"context_length={context_length}  voice_shape={list(voice.shape)}")

rows = []
for L in (507, 508, 509, 510, 511):
    # The assert KModel.forward applies: len(input_ids) + 2 <= context_length
    assert_ok = (L + 2) <= context_length
    voice_row_idx = min(L, 509)
    row = {
        "len": L,
        "assert_passes": assert_ok,
        "voice_row_index": voice_row_idx,
        "voice_row_in_table": voice_row_idx < voice.shape[0],
    }
    # Only run real inference when the assert passes AND we want to sample a
    # couple of representative lengths (keep total runtime bounded).
    if assert_ok and L in (507, 510):
        t0 = time.monotonic()
        with torch.inference_mode():
            audio = model("e" * L, voice[voice_row_idx], speed=1.0)
        a = audio.detach().cpu().numpy()
        row["ran_inference"] = True
        row["infer_seconds"] = round(time.monotonic() - t0, 1)
        row["audio_samples"] = int(a.size)
        row["audio_duration_s"] = round(float(a.size) / 24000, 2)
        row["finite"] = bool(np.isfinite(a).all())
        row["peak"] = float(np.max(np.abs(a)))
    rows.append(row)

print(json.dumps(rows, indent=2))

# Derive the verified rule.
hard_limit = context_length - 2
print("VERIFIED RULE:")
print(f"  hard BERT-context max phonemes per call = {hard_limit}")
print(f"  voice style table rows = {voice.shape[0]} (lengths 0..{voice.shape[0]-1})")
print(f"  voice row used = min(len(ipa), 509); for len>509 it reuses row 509")
print(f"  reference speak.py operational cap = 507 (conservative)")