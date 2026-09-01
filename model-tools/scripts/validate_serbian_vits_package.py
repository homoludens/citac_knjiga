#!/usr/bin/env python3
"""Validate an external Serbian VITS package without installing it."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from zipfile import BadZipFile, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qualification.qualification import PACKAGE_SCHEMA, validate_package_entries, validate_vits_package_roles


def validate(path: Path) -> dict:
    try:
        with ZipFile(path) as archive:
            names = [info.filename for info in archive.infolist()]
            manifest_name = "manifest.json"
            if manifest_name not in names:
                raise ValueError("VITS package manifest.json is missing")
            manifest = json.loads(archive.read(manifest_name))
    except (BadZipFile, OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read VITS package: {error}") from error
    if manifest.get("schema") != PACKAGE_SCHEMA:
        raise ValueError("package schema is not serbian-vits-model-package:1")
    candidate = manifest.get("candidate", {})
    if candidate.get("model_id") != "daremc86/sr-cv-vits" or candidate.get("revision") != "83dc1e1b95d85b9f5602dc94909706fc83dfbc6c" or candidate.get("speaker") != {"label": "Dragana", "id": 0}:
        raise ValueError("package candidate identity is not the pinned Dragana model")
    declared = [entry["path"] for entry in manifest.get("entries", [])]
    validate_package_entries(names, declared + [manifest_name])
    validate_vits_package_roles([entry["role"] for entry in manifest["entries"]])
    if manifest.get("legal") != "ALLOWED":
        raise ValueError("package legal status is not ALLOWED")
    attribution = manifest.get("attribution", {})
    if attribution.get("license") != "CC-BY-4.0" or not attribution.get("source_url") or not attribution.get("modification_notice"):
        raise ValueError("package attribution or modification notice is incomplete")
    qualification = manifest.get("qualification", {})
    if qualification.get("status") != "PASS" or qualification.get("api") != 33 or qualification.get("abi") != "arm64-v8a":
        raise ValueError("package is not qualified on API 33 arm64-v8a")
    graph = manifest.get("graph_contract", {})
    if graph.get("status") != "INSPECTED" or graph.get("external_data") or graph.get("network_access") or graph.get("operator_domains") != ["ai.onnx"]:
        raise ValueError("package graph contract is not self-contained standard ONNX")
    if manifest.get("preprocessing") != {"identity": "serbian-vits-preprocessing-v1", "unsupported_input": "diagnostic"}:
        raise ValueError("package preprocessing contract is not declared")
    if manifest.get("resampler") != {"identity": "serbian-vits-resampler-v1", "native_rate_hz": 22050, "final_rate_hz": 24000, "channels": 1}:
        raise ValueError("package resampler contract is not declared")
    with ZipFile(path) as archive:
        for entry in manifest["entries"]:
            payload = archive.read(entry["path"])
            if len(payload) != entry["size_bytes"] or hashlib.sha256(payload).hexdigest() != entry["sha256"]:
                raise ValueError(f"package checksum mismatch: {entry['path']}")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    args = parser.parse_args()
    try:
        manifest = validate(args.package)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    print(json.dumps({"ok": True, "schema": manifest["schema"], "model_id": manifest["candidate"]["model_id"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
