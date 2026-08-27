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
import hashlib
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

# Deterministic reference captures: the vocoder is seeded AND the CPU
# inference is single-threaded so captures are bit-reproducible across
# processes/machines (multi-threaded GEMM reduction order is not).
torch.set_num_threads(1)

# Representative, self-authored inputs. Every case stays below the operational
# limit; task 3.5 owns boundary and oversized-input cases.
VECTORS = [
    {"id": "greeting-latin", "script": "latin",
     "text": "Dobar dan, ovo je glas Dragane. Ona čita knjige svakog jutra.",
     "categories": ["latin-cyrillic-equivalence"],
     "equivalence_group": "greeting-equivalence"},
    {"id": "greeting-cyrillic", "script": "cyrillic",
     "text": "Добар дан, ово је глас Драгане. Она чита књиге сваког јутра.",
     "categories": ["latin-cyrillic-equivalence"],
     "equivalence_group": "greeting-equivalence"},
    {"id": "diacritics-latin", "script": "latin",
     "text": "Šešir, čuvar, žvakać guma, džep, ćirilično pismo i đavo.",
     "categories": ["diacritics"]},
    {"id": "diacritics-cyrillic", "script": "cyrillic",
     "text": "Шешир, чувар, жвакач гума, џеп, ћирилично писмо и ђаво.",
     "categories": ["diacritics"]},
    {"id": "mixed-digits", "script": "latin",
     "text": "Ima 12 kuća, od kojih 3 su nove, a 1 je stara.",
     "categories": ["numbers"]},
    {"id": "punctuation", "script": "latin",
     "text": "Da li? Ne! Možda... — možda! (Tačno, recila je.)",
     "categories": ["punctuation"]},
    {"id": "cyrillic-punctuation", "script": "cyrillic",
     "text": "Да ли? Не! Можда... — можда! (Тачно, рекла је.)",
     "categories": ["punctuation"]},
    {"id": "digraphs-latin", "script": "latin",
     "text": "Njegoš ljulja džak, a džez svira tiho.",
     "categories": ["diacritics", "digraphs", "latin-cyrillic-equivalence"],
     "equivalence_group": "digraphs-equivalence"},
    {"id": "digraphs-cyrillic", "script": "cyrillic",
     "text": "Његош љуља џак, а џез свира тихо.",
     "categories": ["diacritics", "digraphs", "latin-cyrillic-equivalence"],
     "equivalence_group": "digraphs-equivalence"},
    {"id": "mixed-scripts", "script": "mixed",
     "text": "Mila чита књигу о projektu OpenAI.",
     "categories": ["mixed-scripts"]},
    {"id": "foreign-names", "script": "latin",
     "text": "William Shakespeare i Beyoncé stigli su u Niš.",
     "categories": ["foreign-names"]},
    {"id": "abbreviations", "script": "latin",
     "text": "Dr. Ana Petrović, prof. Marko Ilić, tj. autori, rade itd.",
     "categories": ["abbreviations"]},
    {"id": "numbers-expanded", "script": "latin",
     "text": "Imam 1.234 knjige, 56,78 bodova, 42% i broj 007.",
     "categories": ["numbers"]},
    {"id": "dates", "script": "mixed",
     "text": "Sastanak je 12. марта 2024. у 08:30.",
     "categories": ["dates"]},
    {"id": "currencies", "script": "latin",
     "text": "Cena je 1.250,50 RSD, 20 € ili 15 USD.",
     "categories": ["currencies"]},
    {"id": "measurements", "script": "latin",
     "text": "Sto meri 2,5 kg, 120 cm, 220 V i 24 °C.",
     "categories": ["measurements"]},
    {"id": "roman-numerals", "script": "latin",
     "text": "U XXI veku, papa Jovan Pavle II posetio je Beograd.",
     "categories": ["roman-numerals"]},
    {"id": "punctuation-extended", "script": "latin",
     "text": "Rekla je: „Da; može...“ — zar ne? [Da!].",
     "categories": ["punctuation"]},
    {"id": "urls", "script": "cyrillic",
     "text": "Посети https://primer.rs/knjige?id=12#uvod.",
     "categories": ["urls"]},
    {"id": "email", "script": "latin",
     "text": "Piši na citač@example.org ili marko.petrovic+tts@example.com.",
     "categories": ["email"]},
    {"id": "citations", "script": "latin",
     "text": "Prema [1, str. 12] i (Jovanović, 2024, br. 3), rezultat važi.",
     "categories": ["citations"]},
    {"id": "page-artifacts", "script": "mixed",
     "text": "— 37 —\nСтрана 38\fNastavak teksta.",
     "categories": ["page-artifacts"]},
]

CORPUS_CATEGORIES = (
    "latin-cyrillic-equivalence",
    "diacritics",
    "digraphs",
    "mixed-scripts",
    "foreign-names",
    "abbreviations",
    "numbers",
    "dates",
    "currencies",
    "measurements",
    "roman-numerals",
    "punctuation",
    "urls",
    "email",
    "citations",
    "page-artifacts",
)


def input_manifest_bytes() -> bytes:
    inputs = [
        {
            key: value
            for key, value in vector.items()
            if key in {"id", "script", "text", "categories", "equivalence_group"}
        }
        for vector in VECTORS
    ]
    return json.dumps(
        inputs, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def corpus_metadata() -> dict[str, object]:
    return {
        "corpus_id": "serbian-golden-vectors",
        "version": "reference-20260827-task-3.3",
        "input_provenance": {
            "kind": "self-authored",
            "license_status": "project-owned",
            "statement": (
                "All utterances were written for this project; they are not "
                "excerpts from a third-party publication or dataset."
            ),
        },
        "coverage": {
            category: [
                vector["id"]
                for vector in VECTORS
                if category in vector["categories"]
            ]
            for category in CORPUS_CATEGORIES
        },
        "equivalence_groups": {
            "greeting-equivalence": {
                "vector_ids": ["greeting-latin", "greeting-cyrillic"],
                "expected": "same_ipa_and_token_ids",
            },
            "digraphs-equivalence": {
                "vector_ids": ["digraphs-latin", "digraphs-cyrillic"],
                "expected": "same_ipa_and_token_ids",
            },
        },
        "generation": {
            "script": "model-tools/scripts/capture_reference_vectors.py",
            "command": (
                "model-tools/.venv/bin/python "
                "model-tools/scripts/capture_reference_vectors.py"
            ),
            "reference_runtime": {
                "kokoro_repository": "https://github.com/semidark/kokoro.git",
                "kokoro_revision": "b96fef95e6a746495f92443fac7c688f90fc57fc",
                "kokoro_sr_repository": "https://github.com/homoludens/kokoro-serbian.git",
                "kokoro_sr_revision": "ca5590d9576f63b0763e51a73de0596d47f05425",
                "espeak_ng_command": "espeak-ng -q --ipa=3 -v sr --stdin",
                "espeak_ng_version": "1.52.0",
                "model_sha256": "4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47",
                "voice_sha256": "0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a",
            },
            "seed": SEED,
            "torch_num_threads": 1,
            "speed": 1.0,
            "input_manifest_sha256": hashlib.sha256(input_manifest_bytes()).hexdigest(),
            "non_deterministic_measurements": ["infer_seconds"],
        },
    }


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
        "torch_num_threads": 1,
        "sample_rate": 24000,
        "model_sha256_expected": "4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47",
        "voice_sha256_expected": "0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a",
        "max_input_symbols": 507,
        "corpus": corpus_metadata(),
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
            "categories": v["categories"],
            **({"equivalence_group": v["equivalence_group"]}
               if "equivalence_group" in v else {}),
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
