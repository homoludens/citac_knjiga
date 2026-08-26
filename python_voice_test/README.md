# Dragana Serbian TTS

This folder is a self-contained inference bundle. It reads Serbian text in
either Latin or Cyrillic and writes a 24 kHz WAV file.

## Install once

Use Python 3.11 (or another version supported by the pinned Kokoro package),
then install eSpeak NG and the Python packages:

```bash
# Debian / Ubuntu
sudo apt install espeak-ng

python -m venv .venv
source .venv/bin/activate
python -m pip install --force-reinstall -r requirements.txt
```

## Speak text

```bash
python speak.py --text "Dobar dan, ovo je glas Dragane." --output dragana.wav
python speak.py --text "Добар дан, ово је глас Драгане." --output dragana-cyrillic.wav
```

Kokoro runs on the CPU by default. On an NVIDIA CUDA machine, use
`--device cuda`; adjust the pace with `--speed`, for example `--speed 1.1`.

Keep `speak.py`, `config.json`, `kokoro-serbian-dragana.pth`, and
`sr_dragana.pt` together. Long passages must be split into short sentences;
Kokoro accepts at most 507 phoneme symbols per call.

The requirements deliberately install the exact Kokoro Git revision used for
training. The PyPI `kokoro==0.9.4` package looks compatible but will produce
noise with this model; reinstall from `requirements.txt` if it was installed.
