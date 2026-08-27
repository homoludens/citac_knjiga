#!/usr/bin/env python3
"""Validate and inventory the exported Dragana ONNX graph (task 2.3).

The interface manifest remains the source of declared input limits. This
script validates that contract against the graph, runs the ONNX checker and
ONNX Runtime, inventories graph operators and initializers, detects external
data, and records an isolated process RSS probe for representative and
maximum-declared inputs. It deliberately does not evaluate parity thresholds;
those belong to task 2.4.

Usage from the repository root::

    model-tools/.venv/bin/python model-tools/scripts/validate_onnx.py

The JSON report is written to ``model-tools/export/validation.json`` and is
also emitted on stdout.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import resource
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any

import onnx

REPO = Path(__file__).resolve().parents[2]
DEFAULT_MODEL = REPO / "model-tools" / "export" / "dragana.onnx"
DEFAULT_MANIFEST = REPO / "model-tools" / "export" / "manifest.json"
DEFAULT_VECTORS = REPO / "model-tools" / "reference" / "vectors.json"
DEFAULT_REPORT = REPO / "model-tools" / "export" / "validation.json"

ONNX_DTYPE_NAMES = {
    onnx.TensorProto.FLOAT: "float32",
    onnx.TensorProto.UINT8: "uint8",
    onnx.TensorProto.INT8: "int8",
    onnx.TensorProto.UINT16: "uint16",
    onnx.TensorProto.INT16: "int16",
    onnx.TensorProto.INT32: "int32",
    onnx.TensorProto.INT64: "int64",
    onnx.TensorProto.STRING: "string",
    onnx.TensorProto.BOOL: "bool",
    onnx.TensorProto.FLOAT16: "float16",
    onnx.TensorProto.DOUBLE: "float64",
    onnx.TensorProto.UINT32: "uint32",
    onnx.TensorProto.UINT64: "uint64",
    onnx.TensorProto.COMPLEX64: "complex64",
    onnx.TensorProto.COMPLEX128: "complex128",
    onnx.TensorProto.BFLOAT16: "bfloat16",
    onnx.TensorProto.FLOAT8E4M3FN: "float8e4m3fn",
    onnx.TensorProto.FLOAT8E4M3FNUZ: "float8e4m3fnuz",
    onnx.TensorProto.FLOAT8E5M2: "float8e5m2",
    onnx.TensorProto.FLOAT8E5M2FNUZ: "float8e5m2fnuz",
}

ONNX_DTYPE_BYTES = {
    onnx.TensorProto.FLOAT: 4,
    onnx.TensorProto.UINT8: 1,
    onnx.TensorProto.INT8: 1,
    onnx.TensorProto.UINT16: 2,
    onnx.TensorProto.INT16: 2,
    onnx.TensorProto.INT32: 4,
    onnx.TensorProto.INT64: 8,
    onnx.TensorProto.BOOL: 1,
    onnx.TensorProto.FLOAT16: 2,
    onnx.TensorProto.DOUBLE: 8,
    onnx.TensorProto.UINT32: 4,
    onnx.TensorProto.UINT64: 8,
    onnx.TensorProto.COMPLEX64: 8,
    onnx.TensorProto.COMPLEX128: 16,
    onnx.TensorProto.BFLOAT16: 2,
    onnx.TensorProto.FLOAT8E4M3FN: 1,
    onnx.TensorProto.FLOAT8E4M3FNUZ: 1,
    onnx.TensorProto.FLOAT8E5M2: 1,
    onnx.TensorProto.FLOAT8E5M2FNUZ: 1,
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def graph_shape(value_info: onnx.ValueInfoProto) -> list[Any]:
    tensor = value_info.type.tensor_type
    result: list[Any] = []
    for dim in tensor.shape.dim:
        if dim.HasField("dim_value"):
            result.append(int(dim.dim_value))
        elif dim.HasField("dim_param"):
            result.append(dim.dim_param)
        else:
            result.append(None)
    return result


def tensor_dtype(value_info: onnx.ValueInfoProto) -> str | None:
    return ONNX_DTYPE_NAMES.get(value_info.type.tensor_type.elem_type)


def external_data_dict(tensor: onnx.TensorProto) -> dict[str, str]:
    return {entry.key: entry.value for entry in tensor.external_data}


def tensor_element_count(dims: list[int]) -> int:
    return math.prod(dims) if dims else 1


def initializer_info(tensor: onnx.TensorProto) -> dict[str, Any]:
    dims = [int(dim) for dim in tensor.dims]
    element_count = tensor_element_count(dims)
    external = external_data_dict(tensor)
    dtype_bytes = ONNX_DTYPE_BYTES.get(tensor.data_type)
    logical_bytes = (
        element_count * dtype_bytes if dtype_bytes is not None else None
    )
    if tensor.data_type == onnx.TensorProto.STRING and tensor.string_data:
        logical_bytes = sum(len(value) for value in tensor.string_data)

    if tensor.raw_data:
        stored_bytes = len(tensor.raw_data)
        storage = "raw_data"
    elif external.get("length") is not None:
        stored_bytes = int(external["length"])
        storage = "external_data"
    elif tensor.string_data:
        stored_bytes = sum(len(value) for value in tensor.string_data)
        storage = "typed_field"
    else:
        stored_bytes = logical_bytes
        storage = "typed_field" if logical_bytes is not None else "unknown"

    return {
        "name": tensor.name,
        "dtype": ONNX_DTYPE_NAMES.get(tensor.data_type, str(tensor.data_type)),
        "shape": dims,
        "element_count": element_count,
        "logical_bytes": logical_bytes,
        "stored_bytes": stored_bytes,
        "storage": storage,
        "data_location": "EXTERNAL" if tensor.data_location == onnx.TensorProto.EXTERNAL else "DEFAULT",
        "external_data": external,
    }


def inventory_graph(model: onnx.ModelProto) -> dict[str, Any]:
    operator_counts = Counter((node.domain or "ai.onnx", node.op_type) for node in model.graph.node)
    operators = [
        {"domain": domain, "op_type": op_type, "count": count}
        for (domain, op_type), count in sorted(operator_counts.items())
    ]
    initializers = [initializer_info(tensor) for tensor in model.graph.initializer]
    external = [item for item in initializers if item["external_data"] or item["data_location"] == "EXTERNAL"]
    logical_sizes = [item["logical_bytes"] for item in initializers]
    stored_sizes = [item["stored_bytes"] for item in initializers]
    return {
        "node_count": len(model.graph.node),
        "operators": operators,
        "operator_type_count": len(operators),
        "initializer_count": len(initializers),
        "initializers": initializers,
        "initializer_summary": {
            "total_logical_bytes": sum(size for size in logical_sizes if size is not None),
            "total_stored_bytes": sum(size for size in stored_sizes if size is not None),
            "unknown_size_count": sum(size is None for size in logical_sizes),
        },
        "external_data": {
            "detected": bool(external),
            "initializer_count": len(external),
            "initializers": [
                {
                    "name": item["name"],
                    "location": item["external_data"].get("location"),
                    "offset": item["external_data"].get("offset"),
                    "length": item["external_data"].get("length"),
                }
                for item in external
            ],
        },
    }


def graph_interface(model: onnx.ModelProto) -> dict[str, Any]:
    inputs = {
        value.name: {"dtype": tensor_dtype(value), "shape": graph_shape(value)}
        for value in model.graph.input
    }
    outputs = {
        value.name: {"dtype": tensor_dtype(value), "shape": graph_shape(value)}
        for value in model.graph.output
    }
    return {"inputs": inputs, "outputs": outputs}


def declared_input_limits(manifest: dict[str, Any], graph: dict[str, Any]) -> dict[str, Any]:
    declared = manifest["interface"]["inputs"]
    limits: dict[str, Any] = {}
    for name, spec in declared.items():
        limits[name] = {
            "dtype": spec["dtype"],
            "declared_shape": spec["shape"],
            "declared_bounds": spec.get("bounds", {}),
            "graph_dtype": graph["inputs"].get(name, {}).get("dtype"),
            "graph_shape": graph["inputs"].get(name, {}).get("shape"),
            "limit_source": "model-tools/export/manifest.json",
        }
    return {
        "inputs": limits,
        "max_input_tokens": manifest.get("max_input_tokens"),
        "note": (
            "ONNX dynamic dimensions do not encode min/max values; these are "
            "the declared desktop boundary limits and must be enforced by callers."
        ),
    }


def manifest_checks(
    model_path: Path,
    manifest: dict[str, Any],
    model: onnx.ModelProto,
    inventory: dict[str, Any],
    interface: dict[str, Any],
) -> dict[str, Any]:
    checks: dict[str, bool] = {}
    checks["sha256"] = sha256_file(model_path) == manifest["onnx_sha256"]
    checks["file_size"] = model_path.stat().st_size == manifest["onnx_size_bytes"]
    checks["node_count"] = inventory["node_count"] == manifest["node_count"]
    checks["initializer_count"] = inventory["initializer_count"] == manifest["initializer_count"]
    checks["ir_version"] = model.ir_version == manifest["ir_version"]
    checks["opset_imports"] = [
        {"domain": item.domain or "ai.onnx", "version": int(item.version)}
        for item in model.opset_import
    ] == manifest["opset_imports"]
    expected_interface = {
        "inputs": {
            name: {"dtype": value["dtype"], "shape": value["shape"]}
            for name, value in manifest["interface"]["observed_graph_boundary"]["inputs"].items()
        },
        "outputs": {
            name: {"dtype": value["dtype"], "shape": value["shape"]}
            for name, value in manifest["interface"]["observed_graph_boundary"]["outputs"].items()
        },
    }
    checks["interface"] = interface == expected_interface
    return {"ok": all(checks.values()), "checks": checks}


def rss_bytes() -> int | None:
    try:
        with open("/proc/self/status", encoding="ascii") as stream:
            for line in stream:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) * 1024
    except (FileNotFoundError, OSError, ValueError):
        return None
    return None


def max_rss_bytes() -> int:
    # Linux reports ru_maxrss in KiB; the fallback keeps the unit explicit if
    # this probe is reused on macOS.
    value = int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    return value * 1024 if platform.system() != "Darwin" else value


def runtime_probe(model_path: Path, vectors_path: Path) -> dict[str, Any]:
    import numpy as np
    import onnxruntime as ort

    vectors = json.loads(vectors_path.read_text(encoding="utf-8"))
    representative = vectors["vectors"][0]
    representative_ids = np.asarray([representative["token_ids"]], dtype=np.int64)
    interior = representative_ids[0, 1:-1]
    maximum_ids = np.resize(interior, 512).astype(np.int64, copy=False)[None, :]
    maximum_ids[:, 0] = 0
    maximum_ids[:, -1] = 0
    ref_s = np.zeros((1, 256), dtype=np.float32)
    speed = np.asarray(1.0, dtype=np.float32)

    baseline_rss = rss_bytes()
    session_options = ort.SessionOptions()
    session_options.log_severity_level = 3
    started = time.monotonic()
    session = ort.InferenceSession(
        str(model_path), sess_options=session_options, providers=["CPUExecutionProvider"]
    )
    session_seconds = time.monotonic() - started
    after_session_rss = rss_bytes()

    def run_probe(name: str, input_ids: np.ndarray) -> dict[str, Any]:
        started = time.monotonic()
        outputs = session.run(
            None,
            {"input_ids": input_ids, "ref_s": ref_s, "speed": speed},
        )
        elapsed = time.monotonic() - started
        return {
            "name": name,
            "input_pattern": (
                "reference interior tokens repeated to the declared maximum"
                if name == "maximum_declared_input"
                else "reference vector"
            ),
            "input_shape": list(input_ids.shape),
            "input_tokens": int(input_ids.shape[1]),
            "input_bytes": int(input_ids.nbytes + ref_s.nbytes + speed.nbytes),
            "outputs": [
                {
                    "name": output_name,
                    "shape": list(np.asarray(value).shape),
                    "dtype": str(np.asarray(value).dtype),
                    "bytes": int(np.asarray(value).nbytes),
                }
                for output_name, value in zip(
                    [item.name for item in session.get_outputs()], outputs
                )
            ],
            "inference_seconds": round(elapsed, 3),
            "rss_after_inference_bytes": rss_bytes(),
        }

    probes = [
        run_probe(f"reference:{representative['id']}", representative_ids),
        run_probe("maximum_declared_input", maximum_ids),
    ]
    peak = max(max_rss_bytes(), *(probe["rss_after_inference_bytes"] or 0 for probe in probes))
    return {
        "ok": True,
        "onnxruntime_version": ort.__version__,
        "providers": session.get_providers(),
        "session_options": {
            "graph_optimization": "ORT_ENABLE_ALL (default)",
            "intra_op_num_threads": "default",
        },
        "session_creation_seconds": round(session_seconds, 3),
        "probes": probes,
        "process_memory": {
            "measurement": "isolated child process RSS; includes ONNX Runtime session and model allocations",
            "baseline_rss_bytes": baseline_rss,
            "rss_after_session_bytes": after_session_rss,
            "session_rss_delta_bytes": (
                after_session_rss - baseline_rss
                if baseline_rss is not None and after_session_rss is not None
                else None
            ),
            "peak_rss_bytes": peak,
            "peak_rss_delta_from_baseline_bytes": (
                peak - baseline_rss if baseline_rss is not None else None
            ),
            "max_rss_source": "resource.getrusage(RUSAGE_SELF).ru_maxrss",
        },
    }


def run_runtime_probe(model_path: Path, vectors_path: Path) -> int:
    try:
        result = runtime_probe(model_path, vectors_path)
    except Exception as exc:  # pragma: no cover - exercised by failed environments
        result = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
    print(json.dumps(result, indent=2))
    return 0 if result["ok"] else 1


def invoke_runtime_probe(model_path: Path, vectors_path: Path) -> dict[str, Any]:
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(__file__).resolve()),
            "--runtime-probe",
            "--model",
            str(model_path),
            "--vectors",
            str(vectors_path),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    try:
        result = json.loads(completed.stdout)
    except json.JSONDecodeError:
        result = {
            "ok": False,
            "error": "runtime probe did not emit JSON",
            "stdout": completed.stdout[-2000:],
            "stderr": completed.stderr[-2000:],
        }
    if completed.returncode != 0:
        result["ok"] = False
    return result


def validate(model_path: Path, manifest_path: Path, vectors_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    model = onnx.load(str(model_path), load_external_data=False)

    checker = {"ok": True, "tool": "onnx.checker.check_model"}
    try:
        onnx.checker.check_model(model)
    except Exception as exc:
        checker = {"ok": False, "tool": "onnx.checker.check_model", "error": str(exc)}

    inventory = inventory_graph(model)
    interface = graph_interface(model)
    contract = manifest_checks(model_path, manifest, model, inventory, interface)
    runtime = invoke_runtime_probe(model_path, vectors_path)
    report = {
        "kind": "onnx-validation-report",
        "task": "build-serbian-audiobook-mvp 2.3",
        "validation_version": 1,
        "model": {
            "path": str(model_path.relative_to(REPO)),
            "sha256": sha256_file(model_path),
            "size_bytes": model_path.stat().st_size,
            "manifest": str(manifest_path.relative_to(REPO)),
        },
        "environment": {
            "python": sys.version.split()[0],
            "onnx_version": onnx.__version__,
            "platform": platform.platform(),
        },
        "checks": {
            "onnx_checker": checker,
            "manifest_contract": contract,
            "onnxruntime": {
                "ok": runtime.get("ok", False),
                "providers": runtime.get("providers", []),
                "error": runtime.get("error"),
            },
        },
        "graph": {
            "ir_version": model.ir_version,
            "opset_imports": [
                {"domain": item.domain or "ai.onnx", "version": int(item.version)}
                for item in model.opset_import
            ],
            "domains": sorted({node.domain for node in model.graph.node}),
            "interface": interface,
            **inventory,
        },
        "input_limits": declared_input_limits(manifest, interface),
        "runtime_memory": runtime,
        "parity_thresholds": {
            "evaluated": False,
            "owner": "task 2.4",
        },
    }
    report["ok"] = bool(
        checker["ok"] and contract["ok"] and runtime.get("ok", False)
    )
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--vectors", type=Path, default=DEFAULT_VECTORS)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--runtime-probe", action="store_true", help=argparse.SUPPRESS)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.runtime_probe:
        return run_runtime_probe(args.model, args.vectors)
    if not args.model.is_file():
        print(json.dumps({"ok": False, "error": f"model not found: {args.model}"}))
        return 1

    report = validate(args.model, args.manifest, args.vectors)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
