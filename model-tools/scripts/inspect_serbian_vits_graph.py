#!/usr/bin/env python3
"""Inspect a VITS ONNX graph without publishing or copying its payload."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def inspect_graph(path: Path) -> dict[str, object]:
    try:
        import onnx
    except ImportError as error:
        raise ValueError("onnx tooling is unavailable") from error
    model = onnx.load(str(path), load_external_data=False)
    if model.ir_version and model.ir_version > 10:
        raise ValueError("ONNX IR version is not supported by the locked inspection contract")
    domains = sorted({node.domain or "ai.onnx" for node in model.graph.node})
    if any(domain != "ai.onnx" for domain in domains):
        raise ValueError(f"custom ONNX operator domain: {domains}")
    if any(node.op_type in {"PythonOp", "ZipMap"} for node in model.graph.node):
        raise ValueError("graph contains a non-runtime-safe operator")
    external = any(initializer.data_location == 1 for initializer in model.graph.initializer)
    if external:
        raise ValueError("external ONNX data is not allowed")
    return {
        "status": "INSPECTED",
        "inputs": [{"name": item.name, "type": str(item.type), "shape": [dim.dim_value or dim.dim_param for dim in item.type.tensor_type.shape.dim]} for item in model.graph.input],
        "outputs": [{"name": item.name, "type": str(item.type), "shape": [dim.dim_value or dim.dim_param for dim in item.type.tensor_type.shape.dim]} for item in model.graph.output],
        "operator_domains": domains,
        "external_data": False,
        "network_access": False,
        "node_count": len(model.graph.node),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("graph", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inspect_graph(args.graph)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    print(json.dumps({"ok": True, "node_count": report["node_count"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
