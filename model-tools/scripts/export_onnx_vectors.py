#!/usr/bin/env python3
"""Export all desktop ONNX parity waveforms for later Android execution.

The audio manifest contains only vector IDs and audio metadata. The separate
inputs sidecar contains token IDs and speed, but never source text. WAV files
use IEEE float32 little-endian samples so Android compares the raw ONNX PCM
without an intermediate PCM16 conversion.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import sys
from typing import Any

import numpy as np

REPO = Path(__file__).resolve().parents[2]
MODEL_TOOLS = REPO / "model-tools"
sys.path.insert(0, str(MODEL_TOOLS))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import onnxruntime as ort  # noqa: E402

from export.wrapper import DraganaExportWrapper  # noqa: E402
from run_parity import load_inputs, repo_relative, sha256_file  # noqa: E402


DEFAULT_MANIFEST = MODEL_TOOLS / "export" / "manifest.json"
DEFAULT_VECTORS = MODEL_TOOLS / "reference" / "vectors.json"


def write_float_wav(path: Path, pcm: np.ndarray, sample_rate_hz: int) -> None:
    data = np.asarray(pcm, dtype="<f4")
    if data.ndim != 1 or data.dtype != np.dtype("<f4"):
        raise ValueError("desktop ONNX waveform must be one-dimensional float32")
    payload = data.tobytes(order="C")
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF", 36 + len(payload), b"WAVE", b"fmt ", 16,
        3, 1, sample_rate_hz, sample_rate_hz * 4, 4, 32, b"data", len(payload),
    )
    path.write_bytes(header + payload)


def _chunk_token_ids(vector: dict[str, Any]) -> list[list[int]]:
    boundaries = vector.get("chunk_boundaries", [{"start": 0, "end": len(vector["phonemes"])}])
    return [
        [0, *vector["token_ids"][boundary["start"] + 1:boundary["end"] + 1], 0]
        for boundary in boundaries
    ]


def _audio_metadata(pcm: np.ndarray) -> dict[str, Any]:
    values = pcm.astype(np.float64)
    return {
        "sample_format": "float32-le",
        "sample_count": int(pcm.size),
        "duration_seconds": pcm.size / 24_000,
        "peak": float(np.max(np.abs(values))),
        "rms": float(np.sqrt(np.mean(np.square(values)))),
        "finite": bool(np.isfinite(pcm).all()),
    }


def export_bundle(
    manifest_path: Path,
    vectors_path: Path,
    output_dir: Path,
    *,
    speed: float = 1.0,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError(f"output directory must be empty: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    audio_dir = output_dir / "audio"
    audio_dir.mkdir()

    interface_manifest, thresholds, model_path, vectors_path = load_inputs(manifest_path, vectors_path)
    vectors_document = json.loads(vectors_path.read_text(encoding="utf-8"))
    vectors = vectors_document["vectors"]
    if len(vectors) != 26:
        raise ValueError(f"reference vector export requires 26 vectors, got {len(vectors)}")
    sample_rate_hz = int(vectors_document["sample_rate"])
    if sample_rate_hz != 24_000 or not np.isfinite(speed) or speed <= 0:
        raise ValueError("export requires 24 kHz vectors and a positive finite speed")
    if not model_path.is_file():
        raise FileNotFoundError(f"ONNX model not found: {model_path}")
    model_sha256 = sha256_file(model_path)
    if model_sha256 != interface_manifest["onnx_sha256"]:
        raise ValueError("ONNX model checksum does not match its interface manifest")

    wrapper = DraganaExportWrapper()
    options = ort.SessionOptions()
    options.log_severity_level = 3
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(model_path), sess_options=options, providers=["CPUExecutionProvider"]
    )
    output_names = [output.name for output in session.get_outputs()]
    if not {"waveform", "pred_dur"}.issubset(output_names):
        raise ValueError(f"ONNX outputs do not expose waveform and pred_dur: {output_names}")

    audio_vectors: list[dict[str, Any]] = []
    input_vectors: list[dict[str, Any]] = []
    for vector in vectors:
        chunks = _chunk_token_ids(vector)
        waveforms = []
        for chunk in chunks:
            row = wrapper.voice_table[min(len(chunk) - 2, 509)].numpy()
            outputs = session.run(None, {
                "input_ids": np.asarray([chunk], dtype=np.int64),
                "ref_s": row,
                "speed": np.asarray(speed, dtype=np.float32),
            })
            output_by_name = dict(zip(output_names, outputs))
            waveform = np.asarray(output_by_name["waveform"])
            pred_dur = np.asarray(output_by_name["pred_dur"])
            if waveform.ndim != 1 or waveform.dtype != np.dtype(np.float32):
                raise ValueError(f"{vector['id']}: ONNX waveform contract is invalid")
            if pred_dur.ndim != 1 or pred_dur.size != len(chunk):
                raise ValueError(f"{vector['id']}: ONNX pred_dur contract is invalid")
            waveforms.append(waveform)
        pcm = np.concatenate(waveforms).astype(np.float32, copy=False)
        if not np.isfinite(pcm).all():
            raise ValueError(f"{vector['id']}: ONNX waveform contains non-finite samples")
        relative_audio = Path("audio") / f"{vector['id']}.wav"
        audio_path = output_dir / relative_audio
        write_float_wav(audio_path, pcm, sample_rate_hz)
        metadata = _audio_metadata(pcm)
        metadata.update({
            "id": vector["id"],
            "audio_file": relative_audio.as_posix(),
            "sample_rate_hz": sample_rate_hz,
            "channels": 1,
            "byte_size": audio_path.stat().st_size,
            "sha256": sha256_file(audio_path),
        })
        audio_vectors.append(metadata)
        input_vectors.append({"id": vector["id"], "token_id_chunks": chunks, "speed": speed})

    provenance = {
        "generator": "model-tools/scripts/export_onnx_vectors.py",
        "interface_manifest": repo_relative(manifest_path),
        "model_file": repo_relative(model_path),
        "model_sha256": model_sha256,
        "reference_vectors": repo_relative(vectors_path),
        "reference_vectors_sha256": sha256_file(vectors_path),
        "thresholds": repo_relative(MODEL_TOOLS / "parity" / "fp32-thresholds-v1.json"),
        "thresholds_version": thresholds["thresholds_version"],
        "onnxruntime_version": ort.__version__,
        "execution_provider": "CPUExecutionProvider",
        "intra_op_threads": 1,
        "inter_op_threads": 1,
        "onnx_graph_randomness": "unseeded",
        "speed": speed,
    }
    audio_manifest = {
        "kind": "desktop-onnx-parity-audio",
        "version": 1,
        "audio": {"sample_rate_hz": sample_rate_hz, "channels": 1, "sample_format": "float32-le-wav"},
        "provenance": provenance,
        "vectors": audio_vectors,
    }
    input_manifest = {
        "kind": "desktop-onnx-parity-inputs",
        "version": 1,
        "audio_manifest": "manifest.json",
        "provenance": provenance,
        "vectors": input_vectors,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(audio_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output_dir / "inputs.json").write_text(
        json.dumps(input_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return audio_manifest, input_manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--vectors", type=Path, default=DEFAULT_VECTORS)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--speed", type=float, default=1.0)
    args = parser.parse_args()
    try:
        audio_manifest, _ = export_bundle(
            args.manifest, args.vectors, args.output_dir, speed=args.speed
        )
    except Exception as exc:
        print(json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}))
        return 1
    print(json.dumps({
        "ok": True,
        "output_dir": str(args.output_dir),
        "vectors": len(audio_manifest["vectors"]),
        "manifest": str(args.output_dir / "manifest.json"),
        "inputs": str(args.output_dir / "inputs.json"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
