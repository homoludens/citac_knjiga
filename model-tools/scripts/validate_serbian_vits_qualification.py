#!/usr/bin/env python3
"""Validate the redacted Serbian VITS qualification record."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
QUALIFICATION = ROOT / "model-tools/qualification"
REPORTS = ROOT / "reports/serbian-vits-qualification"
sys.path.insert(0, str(ROOT / "model-tools"))

from qualification.qualification import (  # noqa: E402
    GATE_ORDER,
    acceptance_eligibility,
    legal_status,
    run_gates,
    validate_android_matrix,
    validate_identity,
    validate_package_entries,
    validate_quality_corpus,
    validate_resampler_manifest,
)


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected an object")
    return value


def validate_schema(value: dict, schema_path: Path) -> None:
    schema = load(schema_path)
    errors = sorted(Draft202012Validator(schema).iter_errors(value), key=lambda error: list(error.path))
    if errors:
        raise ValueError("; ".join(f"{'.'.join(map(str, error.path)) or '<root>'}: {error.message}" for error in errors))


def validate_reports(report_dir: Path = REPORTS) -> dict[str, object]:
    manifest = load(report_dir / "qualification-manifest.json")
    validate_schema(manifest, QUALIFICATION / "qualification-manifest-v1.schema.json")
    validate_identity(manifest)
    validate_resampler_manifest(load(QUALIFICATION / "resampler-v1.json"))
    validate_quality_corpus(load(QUALIFICATION / "quality-corpus-v1.json"))
    validate_android_matrix(load(QUALIFICATION / "android-matrix-v1.json"))
    legal = load(report_dir / "legal-source-record.json")
    validate_schema(legal, QUALIFICATION / "legal-source-record-v1.schema.json")
    if legal_status(legal) not in {"BLOCKED", "ALLOWED"}:
        raise ValueError("legal record did not produce an explicit blocked or allowed decision")
    package = load(report_dir / "package-contract-report.json")
    validate_schema(package, QUALIFICATION / "serbian-vits-model-package-v1.schema.json")
    validate_package_entries(package["declared_entries"], [entry["path"] for entry in package["entries"]])
    gate_summary = load(report_dir / "gate-summary.json")
    validate_schema(gate_summary, QUALIFICATION / "gate-record-v1.schema.json")
    result = run_gates(gate_summary["gates"])
    if result != {"gates": gate_summary["gates"], "outcome": gate_summary["outcome"]}:
        raise ValueError("gate summary is not fail-closed or is not canonical")
    candidate = {"model_id": manifest["candidate"]["model_id"], "revision": manifest["candidate"]["revision"]}
    if acceptance_eligibility({"outcome": gate_summary["outcome"], "candidate": candidate, "gates": gate_summary["gates"]}):
        raise ValueError("rejection evidence unexpectedly became accepted")
    return {"schema": manifest["schema"], "outcome": gate_summary["outcome"], "gates": len(GATE_ORDER), "legal": legal_status(legal)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, default=REPORTS)
    args = parser.parse_args()
    try:
        print(json.dumps(validate_reports(args.reports), indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
