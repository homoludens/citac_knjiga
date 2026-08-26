from pathlib import Path
import sys

root = Path.cwd()
bundle = root / "finished_voice" / "epoch-005"
pinned_kokoro = (
    root
    / "workspace"
    / "kokoro-serbian"
    / "runtime"
    / "upstream"
    / "kokoro-training"
)

# This must happen before importing kokoro.
sys.path.insert(0, str(pinned_kokoro))

import kokoro
import soundfile as sf
import torch
from kokoro import KModel
from kokoro_sr.phonemes import phonemize_serbian

print("Kokoro runtime:", kokoro.__file__)

text = "12 Tema za druženje uz priču, za kratka pitanja i odgovore, za govnoobjave i kukanje o njima, za pohvale i žalbe, za sve, i za svašta."
ipa = phonemize_serbian(text)
print("IPA:", ipa)

# Force CPU first. The known-good artifact sample was generated on CPU.
device = "cpu"

model = KModel(
    repo_id="hexgrad/Kokoro-82M",
    config=str(bundle / "config.json"),
    model=str(bundle / "model.pth"),
).to(device).eval()

voice = torch.load(
    bundle / "voice.pt",
    map_location="cpu",
    weights_only=True,
)

with torch.inference_mode():
    audio = model(
        ipa,
        voice[min(len(ipa), 509)],
        speed=1.2,
    )

audio = audio.detach().cpu().numpy()

output = bundle / "dragana-local-test-cpu.wav"
sf.write(output, audio, 24000, subtype="PCM_16")
print("Created:", output)
