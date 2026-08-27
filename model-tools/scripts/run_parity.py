#!/usr/bin/env python3
"""Run the desktop FP32 PyTorch CPU versus ONNX Runtime parity gate (task 2.5).

The runner consumes the ONNX path and ``fp32-parity-v1`` declaration pinned by
the interface manifest. It evaluates every committed reference vector, writes
machine-readable JSON and a human-readable text report, and exits non-zero for
runtime errors or any failed declared measurement.

Usage from the repository root::

    model-tools/.venv/bin/python model-tools/scripts/run_parity.py

The default outputs are ``model-tools/parity/fp32-parity-report.json`` and
``model-tools/parity/fp32-parity-report.txt``. Thresholds have no CLI override;
changing the declaration requires a new version and manifest review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path
from typing import Any

import torch

torch.set_num_threads(1)

REPO = Path(__file__).resolve().parents[2]
MODEL_TOOLS = REPO / "model-tools"
sys.path.insert(0, str(MODEL_TOOLS))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import onnxruntime as ort  # noqa: E402

from export.wrapper import DraganaExportWrapper  # noqa: E402
from export_onnx import load_export_model  # noqa: E402
from parity.runner import evaluate_vector, summarize_vectors  # noqa: E402
from parity.thresholds import load_thresholds  # noqa: E402

DEFAULT_MANIFEST = MODEL_TOOLS / "export" / "manifest.json"
DEFAULT_VECTORS = MODEL_TOOLS / "reference" / "vectors.json"
DEFAULT_JSON_REPORT = MODEL_TOOLS / "parity" / "fp32-parity-report.json"
DEFAULT_TEXT_REPORT = MODEL_TOOLS / "parity" / "fp32-parity-report.txt"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def repo_relative(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO.resolve()))
    except ValueError:
        return str(path)


def manifest_path(value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else REPO / path


def load_inputs(
    manifest_path_arg: Path, vectors_path: Path
) -> tuple[dict[str, Any], dict[str, Any], Path, Path]:
    manifest = json.loads(manifest_path_arg.read_text(encoding="utf-8"))
    threshold_pin = manifest["parity_thresholds"]
    threshold_path = manifest_path(threshold_pin["path"])
    thresholds = load_thresholds(threshold_path)
    if threshold_pin["version"] != thresholds["thresholds_version"]:
        raise ValueError(
            "manifest parity threshold version does not match its declaration: "
            f"{threshold_pin['version']} != {thresholds['thresholds_version']}"
        )
    if threshold_pin["declared_before_candidate_evaluation"] is not True:
        raise ValueError("manifest does not pin thresholds as declared before evaluation")
    declared_vectors = thresholds["comparison"]["reference_vectors"]
    if Path(declared_vectors) != Path(repo_relative(vectors_path)):
        raise ValueError(
            "threshold declaration reference_vectors does not match the runner input: "
            f"{declared_vectors} != {repo_relative(vectors_path)}"
        )
    model_path = manifest_path(manifest["onnx_file"])
    return manifest, thresholds, model_path, vectors_path


def _runtime_failure(vector_id: str, errors: list[str]) -> dict[str, Any]:
    failures = [f"{vector_id}: runtime error: {error}" for error in errors]
    return {
        "vector": vector_id,
        "errors": errors,
        "failures": failures,
        "metrics": {},
        "ok": False,
    }


def run(
    manifest_path_arg: Path = DEFAULT_MANIFEST,
    vectors_path: Path = DEFAULT_VECTORS,
) -> dict[str, Any]:
    started = time.monotonic()
    manifest, thresholds, model_path, vectors_path = load_inputs(
        manifest_path_arg, vectors_path
    )
    thresholds_path = manifest_path(manifest["parity_thresholds"]["path"])
    vectors_document = json.loads(vectors_path.read_text(encoding="utf-8"))
    vectors = vectors_document.get("vectors")
    if not isinstance(vectors, list) or not vectors:
        raise ValueError("reference vectors must contain a non-empty vectors list")
    vector_ids = [vector.get("id") for vector in vectors]
    if any(not isinstance(vector_id, str) or not vector_id for vector_id in vector_ids):
        raise ValueError("every reference vector must have a non-empty string id")
    if len(set(vector_ids)) != len(vector_ids):
        raise ValueError("reference vector ids must be unique")
    seed = int(vectors_document["seed"])
    torch_threads = int(vectors_document["torch_num_threads"])
    if torch_threads != 1:
        raise ValueError(
            f"reference vectors require torch_num_threads=1, got {torch_threads}"
        )
    if torch.get_num_threads() != torch_threads:
        raise RuntimeError(
            f"torch thread contract is not active: expected {torch_threads}, "
            f"got {torch.get_num_threads()}"
        )
    if int(vectors_document["sample_rate"]) != thresholds["comparison"]["sample_rate_hz"]:
        raise ValueError("reference vector sample rate does not match thresholds")
    if not model_path.is_file():
        raise FileNotFoundError(f"ONNX model not found: {model_path}")
    actual_model_sha256 = sha256_file(model_path)
    if actual_model_sha256 != manifest["onnx_sha256"]:
        raise ValueError(
            "ONNX model checksum mismatch: "
            f"expected {manifest['onnx_sha256']}, got {actual_model_sha256}"
        )

    wrapper = DraganaExportWrapper()
    pytorch_model = load_export_model()
    session_options = ort.SessionOptions()
    session_options.log_severity_level = 3
    session_options.intra_op_num_threads = 1
    session_options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(model_path), sess_options=session_options, providers=["CPUExecutionProvider"]
    )
    output_names = [output.name for output in session.get_outputs()]
    required_outputs = {"waveform", "pred_dur"}
    if not required_outputs.issubset(output_names):
        raise ValueError(
            f"ONNX outputs must include {sorted(required_outputs)}, got {output_names}"
        )

    speed = 1.0
    vector_results: list[dict[str, Any]] = []
    for vector in vectors:
        vector_id = vector["id"]
        errors: list[str] = []
        pytorch_waveform = None
        pytorch_pred_dur = None
        onnx_waveform = None
        onnx_pred_dur = None
        input_ids = None
        ref_s = None

        try:
            input_ids = torch.tensor([vector["token_ids"]], dtype=torch.int64)
            ref_s = wrapper.voice_table[vector["voice_row_index"]].to(torch.float32)
        except Exception as exc:
            errors.append(f"input setup: {type(exc).__name__}: {exc}")

        if not errors:
            try:
                torch.manual_seed(seed)
                with torch.inference_mode():
                    pytorch_waveform, pytorch_pred_dur = pytorch_model(
                        input_ids, ref_s, speed
                    )
            except Exception as exc:  # continue so every vector is attempted
                errors.append(f"PyTorch: {type(exc).__name__}: {exc}")

        if input_ids is not None and ref_s is not None:
            try:
                started_onnx = time.monotonic()
                outputs = session.run(
                    None,
                    {
                        "input_ids": input_ids.numpy(),
                        "ref_s": ref_s.numpy(),
                        "speed": torch.tensor(speed, dtype=torch.float32).numpy(),
                    },
                )
                onnx_seconds = time.monotonic() - started_onnx
                output_by_name = dict(zip(output_names, outputs))
                onnx_waveform = output_by_name["waveform"]
                onnx_pred_dur = output_by_name["pred_dur"]
            except Exception as exc:  # continue so every vector is attempted
                onnx_seconds = None
                errors.append(f"ONNX Runtime: {type(exc).__name__}: {exc}")

        if errors:
            result = _runtime_failure(vector_id, errors)
        else:
            result = evaluate_vector(
                vector_id,
                pytorch_waveform.detach().cpu().numpy(),
                onnx_waveform,
                thresholds,
                sample_rate_hz=int(vectors_document["sample_rate"]),
                expected_sample_count=int(vector["audio_samples"]),
                reference_pred_dur=pytorch_pred_dur.detach().cpu().numpy(),
                candidate_pred_dur=onnx_pred_dur,
            )
            result["onnx_infer_seconds"] = round(onnx_seconds, 4)
        vector_results.append(result)

    failures = [
        failure for result in vector_results for failure in result.get("failures", [])
    ]
    summary = summarize_vectors(vector_results, thresholds)
    report = {
        "kind": "fp32-parity-report",
        "task": "build-serbian-audiobook-mvp 2.5",
        "report_version": 1,
        "ok": bool(vector_results) and not failures and all(
            result.get("ok", False) for result in vector_results
        ),
        "comparison": thresholds["comparison"],
        "thresholds": {
            "path": repo_relative(thresholds_path),
            "version": thresholds["thresholds_version"],
            "schema_version": thresholds["schema_version"],
            "policy": thresholds["policy"],
            "manifest_path": repo_relative(manifest_path_arg),
        },
        "model": {
            "path": repo_relative(model_path),
            "sha256": actual_model_sha256,
            "manifest_sha256": manifest["onnx_sha256"],
        },
        "environment": {
            "python": sys.version.split()[0],
            "torch_version": torch.__version__,
            "onnxruntime_version": ort.__version__,
            "providers": session.get_providers(),
            "torch_num_threads": torch.get_num_threads(),
            "onnx_intra_op_num_threads": session_options.intra_op_num_threads,
            "onnx_inter_op_num_threads": session_options.inter_op_num_threads,
        },
        "run": {
            "seed": seed,
            "speed": speed,
            "sample_rate_hz": int(vectors_document["sample_rate"]),
            "vectors_expected": len(vectors),
            "vectors_evaluated": len(vector_results),
        },
        "summary": summary,
        "failures": failures,
        "vectors": vector_results,
        "elapsed_seconds": round(time.monotonic() - started, 3),
    }
    return report


def format_human_report(report: dict[str, Any]) -> str:
    status = "PASS" if report.get("ok") else "FAIL"
    run_info = report.get("run", {})
    lines = [
        "FP32 Desktop Parity Report",
        "===========================",
        f"Status: {status}",
        f"Task: {report.get('task', 'unknown')}",
        f"Thresholds: {report.get('thresholds', {}).get('version', 'unknown')}",
        f"Vectors: {run_info.get('vectors_evaluated', 0)}/"
        f"{run_info.get('vectors_expected', 0)} evaluated",
        "",
        "Vector results:",
    ]
    for result in report.get("vectors", []):
        vector_status = "PASS" if result.get("ok") else "FAIL"
        details = []
        reference = result.get("reference", {})
        candidate = result.get("candidate", {})
        if reference or candidate:
            details.append(
                f"samples pt={reference.get('sample_count', '?')} "
                f"onnx={candidate.get('sample_count', '?')}"
            )
        failed_metrics = [
            f"{metric}.{measurement}"
            for metric, metric_result in result.get("metrics", {}).items()
            for measurement, measurement_result in metric_result.get(
                "measurements", {}
            ).items()
            if not measurement_result.get("pass", False)
        ]
        if failed_metrics:
            details.append("failed=" + ",".join(failed_metrics))
        if result.get("errors"):
            details.append("runtime error")
        lines.append(f"  {result.get('vector', '?')}: {vector_status} ({'; '.join(details)})")

    lines.extend(["", "Worst-case declared measurements:"])
    for metric, metric_result in report.get("summary", {}).items():
        for measurement, measurement_result in metric_result.get("measurements", {}).items():
            status_marker = "PASS" if measurement_result.get("pass") else "FAIL"
            lines.append(
                f"  {metric}.{measurement}: {status_marker} "
                f"value={measurement_result.get('value')!r} "
                f"{measurement_result.get('comparator')} "
                f"{measurement_result.get('threshold')!r}"
            )
    if report.get("failures"):
        lines.extend(["", "Failures:"])
        lines.extend(f"  - {failure}" for failure in report["failures"])
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--vectors", type=Path, default=DEFAULT_VECTORS)
    parser.add_argument("--json-output", type=Path, default=DEFAULT_JSON_REPORT)
    parser.add_argument("--text-output", type=Path, default=DEFAULT_TEXT_REPORT)
    return parser.parse_args()


def write_report(path: Path, contents: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def main() -> int:
    args = parse_args()
    try:
        report = run(args.manifest, args.vectors)
    except Exception as exc:
        report = {
            "kind": "fp32-parity-report",
            "task": "build-serbian-audiobook-mvp 2.5",
            "report_version": 1,
            "ok": False,
            "error": f"{type(exc).__name__}: {exc}",
            "failures": [f"runner: {type(exc).__name__}: {exc}"],
            "vectors": [],
        }
    write_report(args.json_output, json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    human_report = format_human_report(report)
    write_report(args.text_output, human_report)
    print(human_report, end="")
    print(f"Machine-readable report: {args.json_output}")
    print(f"Human-readable report: {args.text_output}")
    return 0 if report.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
