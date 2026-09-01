#!/usr/bin/env python3
"""Convert the pinned Coqui character VITS model to a deterministic Sherpa package.

The checkpoint and every generated payload are desktop-only inputs. The package
is written to the caller's external output directory and is never an Android
source asset.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any
from zipfile import ZIP_STORED, ZipFile, ZipInfo

MODEL_ID = "daremc86/sr-cv-vits"
REVISION = "83dc1e1b95d85b9f5602dc94909706fc83dfbc6c"
SPEAKER = {"label": "Dragana", "id": 0}
NATIVE_RATE_HZ = 22050
FINAL_RATE_HZ = 24000
SHERPA_REVISION = "34eba5a27220026b5981b633981c53205515067d"
PUNCTUATION = "!+'(),-.:;_?/ "


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def write_json(path: Path, value: Any) -> None:
    path.write_bytes(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n")


def export_model(source: Path, output: Path) -> dict[str, Any]:
    try:
        import onnx
        import torch
        from TTS.api import TTS
    except ImportError as error:
        raise RuntimeError("coqui-tts, torch, and onnx are required") from error

    old_cwd = Path.cwd()
    try:
        os.chdir(source)
        torch.manual_seed(0)
        api = TTS(
            model_path=str(source / "model.pth"),
            config_path=str(source / "config.json"),
            speakers_file_path=str(source / "speaker_ids.json"),
            progress_bar=False,
        )
        model = api.synthesizer.tts_model
        original_export = torch.onnx.export

        def legacy_export(*args: Any, **kwargs: Any) -> Any:
            kwargs["dynamo"] = False
            kwargs["external_data"] = False
            return original_export(*args, **kwargs)

        torch.onnx.export = legacy_export
        try:
            model.export_onnx(str(output), verbose=False)
        finally:
            torch.onnx.export = original_export
    finally:
        os.chdir(old_cwd)

    graph = onnx.load(str(output), load_external_data=False)
    onnx.checker.check_model(graph)
    if any(initializer.external_data for initializer in graph.graph.initializer):
        raise RuntimeError("conversion produced external ONNX data")
    domains = sorted({node.domain or "ai.onnx" for node in graph.graph.node})
    if domains != ["ai.onnx"]:
        raise RuntimeError(f"conversion produced unsupported ONNX domains: {domains}")
    expected_inputs = ["input", "input_lengths", "scales", "sid", "langid"]
    if [item.name for item in graph.graph.input] != expected_inputs or [item.name for item in graph.graph.output] != ["output"]:
        raise RuntimeError("conversion produced an unexpected Coqui VITS tensor name contract")
    expected_types = [onnx.TensorProto.INT64, onnx.TensorProto.INT64, onnx.TensorProto.FLOAT,
                      onnx.TensorProto.INT64, onnx.TensorProto.INT64]
    expected_ranks = [2, 1, 1, 1, 1]
    for item, expected_type, expected_rank in zip(graph.graph.input, expected_types, expected_ranks):
        tensor = item.type.tensor_type
        if tensor.elem_type != expected_type or len(tensor.shape.dim) != expected_rank:
            raise RuntimeError(f"unexpected input contract for {item.name}")
    scales_shape = graph.graph.input[2].type.tensor_type.shape.dim
    if scales_shape[0].dim_value != 3 or graph.graph.input[3].type.tensor_type.shape.dim[0].dim_value != 1 or graph.graph.input[4].type.tensor_type.shape.dim[0].dim_value != 1:
        raise RuntimeError("unexpected fixed Coqui VITS control tensor shape")
    output_tensor = graph.graph.output[0].type.tensor_type
    if output_tensor.elem_type != onnx.TensorProto.FLOAT or len(output_tensor.shape.dim) != 3:
        raise RuntimeError("unexpected Coqui VITS output contract")

    characters = model.tokenizer.characters
    symbols = sorted(characters._char_to_id.items(), key=lambda item: item[1])
    if characters.num_chars != 140 or characters.char_to_id("<BLNK>") != 139:
        raise RuntimeError("unexpected Coqui vocabulary contract")
    tokens = "\n".join(
        str(token_id) if symbol == " " else f"{symbol} {token_id}"
        for symbol, token_id in symbols
    ) + "\n"
    (output.parent / "tokens.txt").write_text(tokens, encoding="utf-8")

    graph.ClearField("metadata_props")
    metadata = (
        ("model_type", "vits"),
        ("comment", "coqui"),
        ("language", "sr"),
        ("frontend", "characters"),
        ("add_blank", "1"),
        ("blank_id", "139"),
        ("bos_id", "0"),
        ("eos_id", "0"),
        ("use_eos_bos", "0"),
        ("pad_id", "0"),
        ("n_speakers", "1"),
        ("sample_rate", str(NATIVE_RATE_HZ)),
        ("punctuation", PUNCTUATION),
    )
    for key, value in metadata:
        item = graph.metadata_props.add()
        item.key = key
        item.value = value
    onnx.save_model(graph, str(output), save_as_external_data=False)
    return {
        "inputs": [{"name": item.name, "type": str(item.type).strip()} for item in graph.graph.input],
        "outputs": [{"name": item.name, "type": str(item.type).strip()} for item in graph.graph.output],
        "operator_domains": domains,
        "external_data": False,
        "node_count": len(graph.graph.node),
        "sample_rate_hz": NATIVE_RATE_HZ,
        "speaker": SPEAKER,
        "token_count": len(symbols),
        "coqui_version": getattr(__import__("TTS"), "__version__", "unknown"),
        "torch_version": torch.__version__,
        "onnx_version": onnx.__version__,
    }


def make_package(source: Path, work: Path, output: Path, graph: dict[str, Any], qualification_status: str) -> dict[str, Any]:
    payloads = {
        "model.onnx": (work / "model.onnx", "onnx"),
        "tokens.txt": (work / "tokens.txt", "tokens"),
        "config.json": (source / "config.json", "configuration"),
        "attribution.json": (work / "attribution.json", "attribution"),
        "notice.json": (work / "notice.json", "notice"),
    }
    entries = [
        {"path": name, "role": role, "sha256": sha256(path), "size_bytes": path.stat().st_size}
        for name, (path, role) in payloads.items()
    ]
    manifest: dict[str, Any] = {
        "schema": "serbian-vits-model-package:1",
        "version": "1.0.0",
        "identity_sha256": "",
        "candidate": {
            "model_id": MODEL_ID,
            "revision": REVISION,
            "speaker": SPEAKER,
        },
        "entries": entries,
        "declared_entries": list(payloads),
        "graph_contract": {
            "status": "INSPECTED",
            "inputs": graph["inputs"],
            "outputs": graph["outputs"],
            "operator_domains": graph["operator_domains"],
            "external_data": False,
            "network_access": False,
        },
        "preprocessing": {"identity": "serbian-vits-preprocessing-v1", "unsupported_input": "diagnostic"},
        "resampler": {
            "identity": "serbian-vits-resampler-v1",
            "native_rate_hz": NATIVE_RATE_HZ,
            "final_rate_hz": FINAL_RATE_HZ,
            "channels": 1,
        },
        "legal": "ALLOWED",
        "attribution": {
            "license": "CC-BY-4.0",
            "source_url": f"https://huggingface.co/{MODEL_ID}/tree/{REVISION}",
            "modification_notice": "Coqui VITS was exported to self-contained Sherpa VITS ONNX with character metadata and deterministic package ordering.",
        },
        "qualification": {"status": qualification_status, "api": 33, "abi": "arm64-v8a"},
        "evidence_hashes": {
            "source_model": sha256(source / "model.pth"),
            "source_config": sha256(source / "config.json"),
            "source_speakers": sha256(source / "speaker_ids.json"),
            "source_languages": sha256(source / "language_ids.json"),
        },
    }
    manifest["identity_sha256"] = hashlib.sha256(canonical(manifest)).hexdigest()
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"

    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_STORED) as archive:
        archive.writestr(ZipInfo("manifest.json", date_time=(1980, 1, 1, 0, 0, 0)), manifest_bytes)
        for name, (path, _) in payloads.items():
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_STORED
            info.external_attr = 0o600 << 16
            archive.writestr(info, path.read_bytes())
    return manifest


def convert(source: Path, output: Path, qualification_status: str = "UNRESOLVED") -> dict[str, Any]:
    if not source.is_dir() or any(not (source / name).is_file() for name in ("model.pth", "config.json", "speaker_ids.json", "language_ids.json")):
        raise ValueError("source must be the fetched pinned model workspace")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="serbian-vits-convert-") as temporary:
        work = Path(temporary)
        graph = export_model(source, work / "model.onnx")
        write_json(work / "attribution.json", {"license": "CC-BY-4.0", "source": f"https://huggingface.co/{MODEL_ID}/tree/{REVISION}", "speaker": SPEAKER})
        write_json(work / "notice.json", {"runtime": "Sherpa-ONNX", "runtime_license": "Apache-2.0", "runtime_revision": SHERPA_REVISION, "modification": "Converted from the pinned Coqui checkpoint for offline Android VITS execution."})
        manifest = make_package(source, work, output / "serbian-vits-1.0.0.zip", graph, qualification_status)
        report = {
            "schema": "serbian-vits-conversion-report:1",
            "candidate": {"model_id": MODEL_ID, "revision": REVISION, "speaker": SPEAKER},
            "converter": {"path": "model-tools/scripts/convert_serbian_vits_to_sherpa.py", "runtime": graph["coqui_version"], "torch": graph["torch_version"], "onnx": graph["onnx_version"], "deterministic": True},
            "sherpa": {"revision": SHERPA_REVISION, "model_type": "vits", "frontend": "characters"},
            "graph": graph,
            "source_hashes": {name: sha256(source / name) for name in ("model.pth", "config.json", "speaker_ids.json", "language_ids.json")},
            "package": {"path": "serbian-vits-1.0.0.zip", "sha256": sha256(output / "serbian-vits-1.0.0.zip"), "identity_sha256": manifest["identity_sha256"]},
            "qualification": manifest["qualification"],
        }
        write_json(output / "conversion-report.json", report)
        write_json(output / "graph-inspection.json", graph)
        return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--qualification-status", choices=("UNRESOLVED", "PASS"), default="UNRESOLVED")
    args = parser.parse_args()
    try:
        report = convert(args.source, args.output, args.qualification_status)
    except (OSError, RuntimeError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    print(json.dumps({"ok": True, "package_sha256": report["package"]["sha256"], "qualification": report["qualification"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
