#!/usr/bin/env python3
"""Create Serbian speech with the included Dragana Kokoro model.

Example:
    python speak.py --text "Dobar dan, ovo je glas Dragane." --output dragana.wav
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import unicodedata
from pathlib import Path


# Kokoro v1 accepts these IPA symbols.  The list matches the model config that
# accompanies this export and lets us fail clearly instead of silently dropping
# an unsupported Serbian phoneme.
KOKORO_SYMBOLS = set(
    '$;:,.!?—…"()“” \u0303ʣʥʦʨᵝꭧAIOQSTWYᵊabcdefhijklmnopqrstuvwxyz'
    "ɑɐɒæβɔɕçɖðʤəɚɛɜɟɡɥɨɪʝɯɰŋɳɲɴøɸθœɹɾɻʁɽʂʃʈʧʊʋʌɣɤχʎʒʔ"
    "ˈˌːʰʲ↓→↗↘ᵻ"
)
INVISIBLE = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"), None)
TIE_BARS = dict.fromkeys(map(ord, "\u0361\u035c"), None)
SAMPLE_RATE = 24_000


def normalize_ipa(ipa: str) -> str:
    """Convert eSpeak Serbian IPA to the symbols expected by Kokoro."""
    ipa = unicodedata.normalize("NFC", ipa).translate(INVISIBLE).translate(TIE_BARS)
    ipa = ipa.replace("\u0329", "")  # syllabic mark: keep the underlying /r/
    ipa = (
        ipa.replace("tʃ", "ʧ")
        .replace("dʒ", "ʤ")
        .replace("tɕ", "ʨ")
        .replace("dʑ", "ʥ")
    )
    return re.sub(r"\s+", " ", ipa).strip()


def phonemize_serbian(text: str) -> str:
    """Phonemize Serbian Latin or Cyrillic text with eSpeak NG."""
    executable = shutil.which("espeak-ng")
    if executable is None:
        raise RuntimeError(
            "eSpeak NG is not installed or is not on PATH. Install package 'espeak-ng'."
        )
    result = subprocess.run(
        [executable, "-q", "--ipa=3", "-v", "sr", "--stdin"],
        input=text,
        text=True,
        encoding="utf-8",
        capture_output=True,
    )
    if result.returncode:
        raise RuntimeError(f"eSpeak Serbian phonemization failed: {result.stderr.strip()}")
    ipa = normalize_ipa(result.stdout)
    unsupported = sorted({character for character in ipa if character not in KOKORO_SYMBOLS})
    if unsupported:
        symbols = ", ".join(f"{character!r} (U+{ord(character):04X})" for character in unsupported)
        raise RuntimeError(f"eSpeak produced unsupported phoneme symbols: {symbols}")
    if not ipa:
        raise RuntimeError("Text produced no Serbian phonemes.")
    return ipa


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate Serbian speech with Dragana.")
    parser.add_argument("--text", required=True, help="Serbian text, in Latin or Cyrillic.")
    parser.add_argument("--output", type=Path, default=Path("output.wav"), help="WAV file path.")
    parser.add_argument(
        "--device", choices=("auto", "cpu", "cuda"), default="auto", help="Inference device."
    )
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed; 1.0 is normal.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.speed <= 0:
        raise ValueError("--speed must be greater than zero.")

    try:
        import numpy as np
        import soundfile as sf
        import torch
        import kokoro.modules
        from kokoro import KModel
    except ImportError as error:
        raise RuntimeError(
            "Missing Python dependency. Run: python -m pip install -r requirements.txt"
        ) from error

    # The PyPI 0.9.4 wheel has a different weight-norm implementation from the
    # revision used to train and export this model.  It loads the checkpoint but
    # produces noise, so fail with an actionable message instead.
    runtime_source = Path(kokoro.modules.__file__).read_text(encoding="utf-8")
    if "torch.nn.utils.parametrizations import weight_norm" not in runtime_source:
        raise RuntimeError(
            "Incompatible Kokoro runtime. Reinstall the pinned requirements with: "
            "python -m pip install --force-reinstall -r requirements.txt"
        )

    # folder = Path(__file__).resolve().parent
    # model_path = folder / "kokoro-serbian-dragana.pth"
    # voice_path = folder / "sr_dragana.pt"
    # config_path = folder / "config.json"

    folder = Path(__file__).resolve().parent.parent/Path("kokoro_sr_dragana_voice")
    print(folder)
    model_path = folder / "kokoro_dragana_sr.pth"
    voice_path = folder / "sr_dragana.pt"
    config_path = folder / "config.json"

    missing = [str(path.name) for path in (model_path, voice_path, config_path) if not path.is_file()]
    if missing:
        raise RuntimeError(f"Missing deployable file(s): {', '.join(missing)}")

    device = args.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    if device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("--device cuda was requested, but CUDA is unavailable.")

    ipa = phonemize_serbian(args.text)
    if len(ipa) > 507:
        raise RuntimeError(
            "Text is too long for one Kokoro pass. Split it into shorter sentences (max 507 IPA symbols)."
        )

    model = KModel(repo_id="hexgrad/Kokoro-82M", config=str(config_path), model=str(model_path))
    model = model.to(device).eval()
    voice = torch.load(voice_path, map_location="cpu", weights_only=True)
    if list(voice.shape) != [510, 1, 256]:
        raise RuntimeError(f"Unexpected voicepack shape: {list(voice.shape)}")

    audio = model(ipa, voice[min(len(ipa), 509)], speed=args.speed).numpy()
    if not np.isfinite(audio).all() or np.max(np.abs(audio)) >= 1.0:
        raise RuntimeError("Generated audio is invalid or clipping; no WAV was written.")

    output = args.output.expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    sf.write(output, audio, SAMPLE_RATE, subtype="PCM_16")
    print(f"Wrote {output.resolve()} ({SAMPLE_RATE} Hz, {device})")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
