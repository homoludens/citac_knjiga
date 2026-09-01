#!/usr/bin/env python3
"""Inspect an external Sherpa VITS ONNX file without modifying the repository."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def inspect(path: Path) -> dict[str, object]:
    if path.suffix != ".onnx" or not path.is_file():
        raise ValueError("an external .onnx model file is required")
    try:
        import onnx
    except ImportError as error:
        raise ValueError("onnx is required for model inspection") from error
    model = onnx.load(str(path), load_external_data=False)
    if model.ir_version <= 0:
        raise ValueError("model IR version is invalid")
    domains = sorted({node.domain or "ai.onnx" for node in model.graph.node})
    if domains != ["ai.onnx"]:
        raise ValueError(f"undeclared ONNX operator domains: {domains}")
    if any(initializer.external_data for initializer in model.graph.initializer):
        raise ValueError("external ONNX data is not allowed")
    return {
        "schema": "serbian-vits-graph-inspection:1",
        "path": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "inputs": [{"name": value.name, "shape": [dimension.dim_value or dimension.dim_param for dimension in value.type.tensor_type.shape.dim]} for value in model.graph.input],
        "outputs": [{"name": value.name, "shape": [dimension.dim_value or dimension.dim_param for dimension in value.type.tensor_type.shape.dim]} for value in model.graph.output],
        "operator_domains": domains,
        "external_data": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = inspect(args.model)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (OSError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    print(json.dumps({"ok": True, "sha256": result["sha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
