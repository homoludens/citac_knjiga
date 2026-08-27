#!/usr/bin/env python3
"""Bounded Sherpa-ONNX compatibility probe for the custom Serbian Kokoro graph.

This is an experiment, not a model packager. It creates disposable Sherpa
adapters (metadata, tokens.txt, and raw voices.bin), never modifies the
checked-in graph or voice, and reports separately on the exact tensor path and
Sherpa's text-to-speech path.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL = REPO_ROOT / "model-tools/export/dragana.onnx"
DEFAULT_BUNDLE = REPO_ROOT / "kokoro_sr_dragana_voice"
DEFAULT_VECTORS = REPO_ROOT / "model-tools/reference/vectors.json"
DEFAULT_PHONEMIZER = Path(
    "/home/homoludens/projekti/kokoro_tts_srpski_2/src"
)
EXPECTED_MODEL_SHA256 = (
    "f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6"
)
EXPECTED_VOICE_SHA256 = (
    "0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a"
)
SHERPA_SOURCE_COMMIT = "34eba5a27220026b5981b633981c53205515067d"
SHERPA_PACKAGE_VERSION = "1.13.6"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def load_assets(bundle: Path, vectors_path: Path) -> tuple[dict, dict, torch.Tensor]:
    config = json.loads((bundle / "config.json").read_text())
    vectors = json.loads(vectors_path.read_text())
    voice_path = bundle / "sr_dragana.pt"
    if sha256(voice_path) != EXPECTED_VOICE_SHA256:
        raise ValueError("Dragana voice checksum does not match the pinned bundle")
    voice = torch.load(voice_path, map_location="cpu", weights_only=True)
    if voice.dtype != torch.float32 or tuple(voice.shape) != (510, 1, 256):
        raise ValueError(f"unexpected Dragana voice tensor: {voice.dtype} {tuple(voice.shape)}")
    return config, vectors, voice.contiguous()


def token_ids(ipa: str, vocab: dict[str, int]) -> list[int]:
    unknown = sorted({symbol for symbol in ipa if symbol not in vocab})
    if unknown:
        raise ValueError(f"IPA contains symbols absent from the custom vocabulary: {unknown}")
    return [0, *(vocab[symbol] for symbol in ipa), 0]


def write_tokens(path: Path, vocab: dict[str, int]) -> None:
    # Sherpa's reader represents the space token by an id-only final line.
    lines = []
    for symbol, identifier in sorted(vocab.items(), key=lambda item: item[1]):
        if symbol == " ":
            lines.append(str(identifier))
        else:
            lines.append(f"{symbol} {identifier}")
    path.write_text("\n".join(lines) + "\n")


def annotate_graph(source: Path, target: Path, voice: torch.Tensor) -> None:
    model = onnx.load(str(source), load_external_data=False)
    metadata = {
        "model_type": "kokoro",
        "language": "Serbian (custom graph; phonemization supplied by caller)",
        "has_espeak": "1",
        "sample_rate": "24000",
        "version": "1",
        "voice": "sr",
        "style_dim": ",".join(str(value) for value in voice.shape),
        "n_speakers": "1",
        "speaker_names": "dragana",
        "comment": "Disposable task-2.7 compatibility metadata; not a release package",
    }
    for key, value in metadata.items():
        entry = model.metadata_props.add()
        entry.key = key
        entry.value = value
    onnx.save(model, str(target))


def check_graph_interface(model_path: Path) -> dict:
    model = onnx.load(str(model_path), load_external_data=False)
    inputs = {
        value.name: [value.type.tensor_type.elem_type,
                     [dim.dim_param or dim.dim_value for dim in value.type.tensor_type.shape.dim]]
        for value in model.graph.input
    }
    outputs = {
        value.name: [value.type.tensor_type.elem_type,
                     [dim.dim_param or dim.dim_value for dim in value.type.tensor_type.shape.dim]]
        for value in model.graph.output
    }
    expected_inputs = {
        "input_ids": [7, [1, "seq_len"]],
        "ref_s": [1, [1, 256]],
        "speed": [1, []],
    }
    expected_outputs = {
        "waveform": [1, ["waveform_len"]],
        "pred_dur": [7, ["pred_dur_len"]],
    }
    if inputs != expected_inputs or outputs != expected_outputs:
        raise ValueError(f"custom graph interface mismatch: inputs={inputs}, outputs={outputs}")
    return {
        "inputs": inputs,
        "outputs": outputs,
        "metadata_before_probe": {
            entry.key: entry.value for entry in model.metadata_props
        },
    }


def check_phonemization(vectors: dict, vocab: dict[str, int], phonemizer_root: Path) -> list[dict]:
    if not phonemizer_root.is_dir():
        raise FileNotFoundError(f"pinned kokoro_sr source is unavailable: {phonemizer_root}")
    sys.path.insert(0, str(phonemizer_root))
    from kokoro_sr.phonemes import phonemize_serbian

    results = []
    for vector in vectors["vectors"]:
        actual_ipa = phonemize_serbian(vector["text"])
        actual_ids = token_ids(actual_ipa, vocab)
        results.append(
            {
                "id": vector["id"],
                "ipa_match": actual_ipa == vector["ipa"],
                "token_ids_match": actual_ids == vector["token_ids"],
                "ipa_len": len(actual_ipa),
                "token_count": len(actual_ids),
            }
        )
    return results


def run_direct_ort(model_path: Path, vector: dict, voice: torch.Tensor, vocab: dict[str, int]) -> dict:
    ids = np.asarray([token_ids(vector["ipa"], vocab)], dtype=np.int64)
    row = np.asarray(voice[min(vector["ipa_len"], 509), 0, :].unsqueeze(0))
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    started = time.monotonic()
    waveform, pred_dur = session.run(
        None,
        {"input_ids": ids, "ref_s": row, "speed": np.asarray(1.0, dtype=np.float32)},
    )
    elapsed = time.monotonic() - started
    waveform = np.asarray(waveform)
    pred_dur = np.asarray(pred_dur)
    return {
        "vector": vector["id"],
        "token_count": int(ids.shape[1]),
        "voice_row": min(vector["ipa_len"], 509),
        "sample_rate": 24000,
        "samples": int(waveform.size),
        "pred_dur_count": int(pred_dur.size),
        "finite": bool(np.isfinite(waveform).all()),
        "peak": float(np.max(np.abs(waveform))),
        "elapsed_seconds": elapsed,
    }


def run_sherpa(model_path: Path, voices_path: Path, tokens_path: Path, data_dir: Path, vectors: dict) -> dict:
    if importlib.util.find_spec("sherpa_onnx") is None:
        return {"status": "not-run", "reason": "sherpa_onnx is not installed in this Python environment"}
    import sherpa_onnx

    if getattr(sherpa_onnx, "__version__", None) != SHERPA_PACKAGE_VERSION:
        return {
            "status": "not-run",
            "reason": f"unexpected sherpa_onnx version: {getattr(sherpa_onnx, '__version__', None)}",
        }
    kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig()
    kokoro.model = str(model_path)
    kokoro.voices = str(voices_path)
    kokoro.tokens = str(tokens_path)
    kokoro.data_dir = str(data_dir)
    model = sherpa_onnx.OfflineTtsModelConfig()
    model.kokoro = kokoro
    model.provider = "cpu"
    model.num_threads = 1
    config = sherpa_onnx.OfflineTtsConfig(model=model, max_num_sentences=1)
    if not config.validate():
        return {"status": "rejected", "reason": "Sherpa configuration validation failed"}
    tts = sherpa_onnx.OfflineTts(config)
    results = []
    for vector in vectors["vectors"]:
        started = time.monotonic()
        audio = tts.generate(vector["text"], sid=0, speed=1.0)
        elapsed = time.monotonic() - started
        samples = np.asarray(audio.samples)
        results.append(
            {
                "id": vector["id"],
                "sample_rate": int(audio.sample_rate),
                "samples": int(samples.size),
                "finite": bool(np.isfinite(samples).all()),
                "peak": float(np.max(np.abs(samples))) if samples.size else 0.0,
                "elapsed_seconds": elapsed,
            }
        )
    return {
        "status": "ran",
        "version": sherpa_onnx.__version__,
        "results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-sherpa", action="store_true", help="also run Sherpa's high-level Kokoro API")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--bundle", type=Path, default=DEFAULT_BUNDLE)
    parser.add_argument("--vectors", type=Path, default=DEFAULT_VECTORS)
    parser.add_argument("--phonemizer-root", type=Path, default=DEFAULT_PHONEMIZER)
    parser.add_argument("--data-dir", type=Path, default=Path("/usr/share/espeak-ng-data"))
    args = parser.parse_args()

    if sha256(args.model) != EXPECTED_MODEL_SHA256:
        raise ValueError("custom ONNX graph checksum does not match the task-2.2 candidate")
    config, vectors, voice = load_assets(args.bundle, args.vectors)
    vocab = {str(symbol): int(identifier) for symbol, identifier in config["vocab"].items()}
    graph = check_graph_interface(args.model)
    phonemization = check_phonemization(vectors, vocab, args.phonemizer_root)
    if not all(item["ipa_match"] and item["token_ids_match"] for item in phonemization):
        raise ValueError(f"custom Serbian preprocessing mismatch: {phonemization}")
    direct_ort = run_direct_ort(args.model, vectors["vectors"][0], voice, vocab)

    with tempfile.TemporaryDirectory(prefix="sherpa-kokoro-probe-") as temp:
        work = Path(temp)
        probe_model = work / "dragana-with-sherpa-metadata.onnx"
        probe_voices = work / "voices.bin"
        probe_tokens = work / "tokens.txt"
        annotate_graph(args.model, probe_model, voice)
        probe_voices.write_bytes(np.asarray(voice.numpy(), dtype="<f4").tobytes())
        write_tokens(probe_tokens, vocab)
        sherpa = run_sherpa(probe_model, probe_voices, probe_tokens, args.data_dir, vectors) if args.run_sherpa else {
            "status": "not-requested"
        }

    report = {
        "experiment": "sherpa-onnx-custom-serbian-kokoro-2.7",
        "custom_model_sha256": sha256(args.model),
        "dragana_voice_sha256": sha256(args.bundle / "sr_dragana.pt"),
        "custom_graph": graph,
        "custom_preprocessing": phonemization,
        "direct_onnx_runtime_probe": direct_ort,
        "sherpa": {
            "source_commit": SHERPA_SOURCE_COMMIT,
            "package_version_expected": SHERPA_PACKAGE_VERSION,
            "data_dir": str(args.data_dir),
            **sherpa,
        },
        "adapter_scope": [
            "metadata is added only to a disposable graph copy",
            "sr_dragana.pt is converted only to disposable little-endian float32 voices.bin",
            "tokens.txt is generated from the custom 178-slot vocabulary",
            "the original graph, voice, and repository lock files are not modified",
        ],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if sherpa.get("status") in {"ran", "not-requested"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
