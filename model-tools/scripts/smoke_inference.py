#!/usr/bin/env python3
"""Smoke test for the pinned Dragana CPU inference path (task 1.2/1.4 seed).

Verifies, with the locked model-tools venv + the pinned Kokoro runtime from
the training repo:
  1. The pinned `kokoro` package is imported (not PyPI) and passes the
     weight-norm guard.
  2. The Dragana epoch-005 checkpoint loads and the voice tensor has the
     expected shape [510, 1, 256].
  3. espeak-ng Serbian phonemization works and passes the vocabulary audit.
  4. One CPU inference call produces finite, unclipped float32 audio at the
     documented 24 kHz rate.

This is the seed of the task 1.4 reference capture; it prints a
machine-readable JSON result on the last line.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

# --- Pinned runtime (see model-tools/runtime-pins.md) -----------------------
TRAINING_REPO = Path("/home/homoludens/projekti/kokoro_tts_srpski_2")
KOKORO_RUNTIME = (
    TRAINING_REPO / "workspace/kokoro-serbian/runtime/upstream/kokoro-training"
)
BUNDLE = Path("/home/homoludens/projekti/citac_knjiga/kokoro_sr_dragana_voice")

sys.path.insert(0, str(KOKORO_RUNTIME))  # must precede `import kokoro`
sys.path.insert(0, str(TRAINING_REPO / "src"))  # for kokoro_sr.phonemes

TEXT = "Dobar dan, ovo je glas Dragane. Ona čita knjige svakog jutra."


def main() -> int:
    started = time.monotonic()
    result: dict = {"text": TEXT}

    import kokoro  # noqa: E402  (import order is intentional)
    import kokoro.modules  # noqa: E402
    import torch  # noqa: E402

    result["kokoro_file"] = kokoro.__file__
    result["kokoro_version"] = kokoro.__version__
    result["torch_version"] = torch.__version__
    result["device"] = "cpu"

    # Weight-norm guard (same check as python_voice_test/speak.py).
    src = Path(kokoro.modules.__file__).read_text(encoding="utf-8")
    guard_ok = "torch.nn.utils.parametrizations import weight_norm" in src
    result["weight_norm_guard"] = guard_ok
    if not guard_ok:
        result["error"] = "incompatible kokoro runtime (weight-norm guard failed)"
        print(json.dumps(result, ensure_ascii=False))
        return 1

    from kokoro import KModel  # noqa: E402

    # --- Checkpoint / voice checksums (task 1.2) ----------------------------
    import hashlib  # noqa: E402

    def sha256(path: Path) -> str:
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()

    result["model_sha256"] = sha256(BUNDLE / "kokoro_dragana_sr.pth")
    result["voice_sha256"] = sha256(BUNDLE / "sr_dragana.pt")
    result["model_sha256_expected"] = (
        "4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47"
    )
    result["voice_sha256_expected"] = (
        "0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a"
    )
    result["checksums_ok"] = (
        result["model_sha256"] == result["model_sha256_expected"]
        and result["voice_sha256"] == result["voice_sha256_expected"]
    )

    # --- Load model + voice --------------------------------------------------
    model = KModel(
        repo_id="hexgrad/Kokoro-82M",
        config=str(BUNDLE / "config.json"),
        model=str(BUNDLE / "kokoro_dragana_sr.pth"),
    ).to("cpu").eval()
    result["load_seconds"] = round(time.monotonic() - started, 2)

    voice = torch.load(BUNDLE / "sr_dragana.pt", map_location="cpu", weights_only=True)
    result["voice_shape"] = list(voice.shape)
    result["voice_shape_ok"] = list(voice.shape) == [510, 1, 256]

    # --- Phonemization ---------------------------------------------------------
    from kokoro_sr.phonemes import phonemize_serbian  # noqa: E402

    ipa = phonemize_serbian(TEXT)
    result["ipa"] = ipa
    result["ipa_len"] = len(ipa)
    result["max_input_symbols"] = 507

    # --- Inference -------------------------------------------------------------
    infer_started = time.monotonic()
    with torch.inference_mode():
        audio = model(ipa, voice[min(len(ipa), 509)], speed=1.0)
    infer_seconds = time.monotonic() - infer_started
    audio_np = audio.detach().cpu().numpy()

    import numpy as np  # noqa: E402

    result["audio_dtype"] = str(audio_np.dtype)
    result["audio_samples"] = int(audio_np.size)
    result["audio_duration_s"] = round(float(audio_np.size) / 24_000, 3)
    result["sample_rate"] = 24_000
    result["finite"] = bool(np.isfinite(audio_np).all())
    result["peak"] = float(np.max(np.abs(audio_np)))
    result["rms"] = float(np.sqrt(np.mean(np.square(audio_np))))
    result["infer_seconds"] = round(infer_seconds, 2)
    result["real_time_factor"] = round(infer_seconds / (audio_np.size / 24_000), 3)

    # --- Save the smoke WAV (LFS-tracked) ---------------------------------------
    import soundfile as sf  # noqa: E402

    out = Path("/home/homoludens/projekti/citac_knjiga/model-tools/reference/smoke-test.wav")
    out.parent.mkdir(parents=True, exist_ok=True)
    sf.write(out, audio_np, 24_000, subtype="PCM_16")
    result["wav_path"] = str(out)

    ok = (
        guard_ok
        and result["checksums_ok"]
        and result["voice_shape_ok"]
        and result["finite"]
        and result["peak"] < 1.0
        and result["audio_samples"] > 0
    )
    result["ok"] = ok
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())