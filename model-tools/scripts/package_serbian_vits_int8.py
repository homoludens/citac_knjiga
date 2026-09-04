#!/usr/bin/env python3
"""Create a versioned Serbian VITS package with a static-QDQ INT8 model."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo

import onnx


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def update_package(source: Path, model: Path, output: Path, version: str, qualification_status: str) -> None:
    if not source.is_file() or not model.is_file():
        raise ValueError("source package and static INT8 model must exist")
    with ZipFile(source) as archive:
        names = [entry.filename for entry in archive.infolist()]
        if len(names) != len(set(names)) or "manifest.json" not in names:
            raise ValueError("source package has invalid entries")
        manifest = json.loads(archive.read("manifest.json"))
        payloads = {name: archive.read(name) for name in names if name != "manifest.json"}

    entries = manifest.get("entries")
    if manifest.get("schema") != "serbian-vits-model-package:1" or not isinstance(entries, list):
        raise ValueError("source package is not a Serbian VITS package")
    model_entry = next((entry for entry in entries if entry.get("role") == "onnx"), None)
    if model_entry is None or model_entry.get("path") not in payloads:
        raise ValueError("source package has no model payload")

    graph = onnx.load(model, load_external_data=False)
    onnx.checker.check_model(graph)
    if any(initializer.external_data for initializer in graph.graph.initializer):
        raise ValueError("static INT8 model uses external ONNX data")
    domains = sorted({node.domain or "ai.onnx" for node in graph.graph.node})
    if domains != ["ai.onnx"]:
        raise ValueError(f"static INT8 model has unsupported ONNX domains: {domains}")

    model_bytes = model.read_bytes()
    payloads[model_entry["path"]] = model_bytes
    model_entry["sha256"] = sha256(model_bytes)
    model_entry["size_bytes"] = len(model_bytes)
    manifest["version"] = version
    manifest["qualification"]["status"] = qualification_status
    manifest["graph_contract"]["operator_domains"] = domains
    manifest["graph_contract"]["external_data"] = False
    manifest["attribution"]["modification_notice"] = (
        "Converted from the pinned Coqui checkpoint to Sherpa VITS ONNX, then "
        "static-QDQ INT8 per-channel Conv quantized for offline Android execution."
    )
    manifest["evidence_hashes"]["static_int8_model"] = model_entry["sha256"]
    manifest["identity_sha256"] = ""
    manifest["identity_sha256"] = sha256(canonical(manifest))
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"

    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_STORED) as archive:
        archive.writestr(ZipInfo("manifest.json", date_time=(1980, 1, 1, 0, 0, 0)), manifest_bytes)
        for name in manifest["declared_entries"]:
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_STORED
            info.external_attr = 0o600 << 16
            archive.writestr(info, payloads[name])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="qualified FP32 VITS package")
    parser.add_argument("model", type=Path, help="static-QDQ INT8 model.onnx")
    parser.add_argument("output", type=Path, help="versioned INT8 package output")
    parser.add_argument("--version", default="1.1.0")
    parser.add_argument("--qualification-status", choices=("PASS", "UNRESOLVED"), required=True)
    args = parser.parse_args()
    update_package(args.source, args.model, args.output, args.version, args.qualification_status)
    print(json.dumps({"ok": True, "package": str(args.output), "sha256": sha256(args.output.read_bytes())}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
