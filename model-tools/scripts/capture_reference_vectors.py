#!/usr/bin/env python3
"""Capture reference vectors from the pinned PyTorch CPU implementation
(task 1.4). For each representative Serbian input it records:

  - input text (Latin and Cyrillic)
  - normalized IPA (phonemize_serbian output)
  - token IDs (the exact int64 sequence KModel.forward builds, incl. 0 pads)
  - pred_dur (per-phoneme frame counts, with the fixed seed)
  - seeded PCM16 WAV (the reference audio)
  - audio metadata (samples, duration, peak, rms, finite)

Reference seed is fixed so the captures are reproducible bit-for-bit. Phase 2
(PyTorch vs ONNX vs Android) compares against these vectors.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

TRAINING_REPO = Path("/home/homoludens/projekti/kokoro_tts_srpski_2")
sys.path.insert(0, str(TRAINING_REPO / "workspace/kokoro-serbian/runtime/upstream/kokoro-training"))
sys.path.insert(0, str(TRAINING_REPO / "src"))
BUNDLE = Path("/home/homoludens/projekti/citac_knjiga/kokoro_sr_dragana_voice")
OUT = Path("/home/homoludens/projekti/citac_knjiga/model-tools/reference")

SEED = 20260826  # fixed reference seed (recorded in vectors.json)

import numpy as np  # noqa: E402
import torch  # noqa: E402
import soundfile as sf  # noqa: E402
from kokoro import KModel  # noqa: E402
from kokoro_sr.phonemes import phonemize_serbian  # noqa: E402

# Representative Serbian inputs (Latin + Cyrillic), short enough for one pass.
VECTORS = [
    {"id": "greeting-latin", "script": "latin",
     "text": "Dobar dan, ovo je glas Dragane. Ona čita knjige svakog jutra."},
    {"id": "greeting-cyrillic", "script": "cyrillic",
     "text": "Добар дан, ово је глас Драгане. Она чита књиге сваког јутра."},
    {"id": "diacritics-latin", "script": "latin",
     "text": "Šešir, čuvar, žvakać guma, džep, ćirilično pismo i đavo."},
    {"id": "diacritics-cyrillic", "script": "cyrillic",
     "text": "Шешир, чувар, жвакач гума, џеп, ћирилично писмо и ђаво."},
    {"id": "mixed-digits", "script": "latin",
     "text": "Ima 12 kuća, od kojih 3 su nove, a 1 je stara."},
    {"id": "punctuation", "script": "latin",
     "text": "Da li? Ne! Možda... — možda! (Tačno, recila je.)"},
    {"id": "cyrillic-punctuation", "script": "cyrillic",
     "text": "Да ли? Не! Можда... — можда! (Тачно, рекла је.)"},
]


def main() -> int:
    model = KModel(
        repo_id="hexgrad/Kokoro-82M",
        config=str(BUNDLE / "config.json"),
        model=str(BUNDLE / "kokoro_dragana_sr.pth"),
    ).to("cpu").eval()
    voice = torch.load(BUNDLE / "sr_dragana.pt", map_location="cpu", weights_only=True)
    vocab = model.vocab

    results = {
        "seed": SEED,
        "sample_rate": 24000,
        "model_sha256_expected": "4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47",
        "voice_sha256_expected": "0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a",
        "max_input_symbols": 507,
        "vectors": [],
    }

    for v in VECTORS:
        text = v["text"]
        t0 = time.monotonic()
        ipa = phonemize_serbian(text)
        # Token IDs exactly as KModel.forward builds them.
        ids = [i for i in (vocab.get(p) for p in ipa) if i is not None]
        input_ids = [0, *ids, 0]
        row_idx = min(len(ipa), 509)
        torch.manual_seed(SEED)
        with torch.inference_mode():
            audio = model(ipa, voice[row_idx], speed=1.0)
        a = audio.detach().cpu().numpy()
        wav = OUT / f"{v['id']}.wav"
        sf.write(wav, a, 24000, subtype="PCM_16")
        results["vectors"].append({
            "id": v["id"],
            "script": v["script"],
            "text": text,
            "ipa": ipa,
            "ipa_len": len(ipa),
            "voice_row_index": row_idx,
            "token_ids": input_ids,
            "token_count": len(input_ids),
            "audio_samples": int(a.size),
            "duration_s": round(float(a.size) / 24000, 3),
            "peak": float(np.max(np.abs(a))),
            "rms": round(float(np.sqrt(np.mean(np.square(a)))), 6),
            "finite": bool(np.isfinite(a).all()),
            "wav": str(wav),
            "infer_seconds": round(time.monotonic() - t0, 2),
        })
        print(f"  {v['id']:24s} ipa={len(ipa):3d} tok={len(input_ids):3d} "
              f"dur={float(a.size)/24000:5.2f}s peak={float(np.max(np.abs(a))):.3f}")

    out_json = OUT / "vectors.json"
    out_json.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", out_json)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())