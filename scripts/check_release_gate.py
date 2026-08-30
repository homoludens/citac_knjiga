#!/usr/bin/env python3
"""Evaluate the task-12.8 publication gate without publishing anything."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TASKS = ROOT / "openspec/changes/build-serbian-audiobook-mvp/tasks.md"
CAPABILITY_AUDIT = ROOT / "reports/task-11-8-mvp-capability-matrix.md"
QUALIFICATION = ROOT / "reports/task-11-4-android-qualification.md"
DEPLOYMENT = ROOT / "DEPLOYMENT.md"
MODEL_FIXTURE = ROOT / "model-tools/package/model-package-v1.example.json"
DESKTOP_PARITY = ROOT / "model-tools/parity/fp32-parity-v2-report.json"
RELEASE_DOCS_CHECK = ROOT / "scripts/validate_release_docs.py"
FDROID_CHECK = ROOT / "scripts/check_fdroid.py"
RELEASE_ARTIFACTS = ROOT / "scripts/release_artifacts.py"

TASK_PATTERN = re.compile(r"^- \[([ xX])\] (\d+(?:\.\d+)?)\b")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def task_status() -> dict[str, bool]:
    statuses: dict[str, bool] = {}
    for line in read(TASKS).splitlines():
        match = TASK_PATTERN.match(line)
        if match:
            statuses[match.group(2)] = match.group(1).lower() == "x"
    return statuses


def task_requirements(statuses: dict[str, bool], task_ids: list[str]) -> str | None:
    missing = [task_id for task_id in task_ids if not statuses.get(task_id, False)]
    return f"unchecked tasks: {', '.join(missing)}" if missing else None


def result(name: str, status: str, evidence: str, reason: str) -> dict[str, str]:
    return {"name": name, "status": status, "evidence": evidence, "reason": reason}


def command_result(command: list[str], name: str, evidence: str) -> dict[str, str]:
    try:
        completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
    except OSError as error:
        return result(name, "FAIL", evidence, f"could not execute check: {error}")
    if completed.returncode == 0:
        return result(name, "PASS", evidence, "check passed")
    details = (completed.stderr or completed.stdout).strip().splitlines()
    detail = details[-1] if details else f"exit status {completed.returncode}"
    return result(name, "FAIL", evidence, detail)


def check_signed_artifacts(artifact_dir: Path | None, statuses: dict[str, bool]) -> dict[str, str]:
    if not statuses.get("12.7", False):
        return result(
            "signed_app_artifacts",
            "BLOCKED",
            "scripts/release_artifacts.py",
            "task 12.7 is unchecked; signed app artifacts are not release evidence",
        )
    if artifact_dir is None:
        return result("signed_app_artifacts", "BLOCKED", "scripts/release_artifacts.py", "no signed artifact directory was supplied")
    artifact_dir = artifact_dir.expanduser().resolve()
    if artifact_dir == ROOT or ROOT in artifact_dir.parents:
        return result("signed_app_artifacts", "FAIL", "external artifact directory", "artifact directory must be outside the repository")
    if not artifact_dir.is_dir():
        return result("signed_app_artifacts", "BLOCKED", "external artifact directory", "signed artifact directory does not exist")
    return command_result(
        [sys.executable, str(RELEASE_ARTIFACTS), "verify", "--artifact-dir", str(artifact_dir)],
        "signed_app_artifacts",
        "release_artifacts.py verify (v2/v3 signature, checksums, metadata, payload scan)",
    )


def check_model_legal(model_manifest: Path | None) -> dict[str, str]:
    path = model_manifest or MODEL_FIXTURE
    try:
        manifest = json.loads(read(path))
        legal = manifest["legal"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        return result("model_legal_status", "FAIL", str(path), f"invalid model manifest: {error}")

    if (
        legal.get("status") == "cleared"
        and legal.get("model_distribution") == "allowed"
        and not legal.get("outstanding_reviews")
        and legal.get("clearance_evidence")
        and manifest.get("artifacts")
        and all(item.get("distribution_status") == "allowed" for item in manifest.get("artifacts", []))
    ):
        return result("model_legal_status", "PASS", str(path), "model distribution is explicitly cleared")
    return result(
        "model_legal_status",
        "BLOCKED",
        str(path),
        "public model-weight legal gate is open; schema declarations are not legal clearance",
    )


def check_parity(android_report: Path | None) -> list[dict[str, str]]:
    try:
        report = json.loads(read(DESKTOP_PARITY))
        desktop_ok = (
            report.get("ok") is True
            and report.get("run", {}).get("vectors_expected") == 26
            and report.get("run", {}).get("vectors_evaluated") == 26
            and report.get("thresholds", {}).get("version") == "fp32-parity-v2"
            and all(value.get("pass") is True for value in report.get("summary", {}).values())
        )
    except (OSError, TypeError, json.JSONDecodeError):
        desktop_ok = False
    desktop = result(
        "desktop_parity",
        "PASS" if desktop_ok else "FAIL",
        "model-tools/parity/fp32-parity-v2-report.json",
        "committed FP32 report covers all 26 vectors" if desktop_ok else "committed desktop parity report is not a passing v2 report",
    )

    if android_report:
        try:
            report = json.loads(read(android_report))
            android_ok = report.get("status") in {"passed", "pass"} and report.get("ok") is True
        except (OSError, TypeError, json.JSONDecodeError):
            android_ok = False
        reason = "Android parity report passed" if android_ok else "Android parity report is missing or not passing"
        android = result("android_parity", "PASS" if android_ok else "FAIL", "external Android parity report", reason)
    else:
        text = read(DEPLOYMENT)
        android_ok = "app-private report has `status=passed`" in text and "against all 26" in text
        android = result(
            "android_parity",
            "PASS" if android_ok else "BLOCKED",
            "DEPLOYMENT.md task 4.9 evidence",
            "historical Poco F3 report passed 26/26; report remains device-private" if android_ok else "no passing Android parity evidence",
        )
    return [desktop, android]


def check_production_model_proof() -> dict[str, str]:
    text = read(DEPLOYMENT)
    markers = ("complete production graph path was verified", "Poco F3", "networking disabled", "24 kHz, mono")
    passed = all(marker in text for marker in markers)
    return result(
        "production_model_proof",
        "PASS" if passed else "BLOCKED",
        "DEPLOYMENT.md task 4.10 evidence",
        "real production graph proof exists on Poco F3; model package is external" if passed else "no complete production-model proof is recorded",
    )


def check_external_players(statuses: dict[str, bool]) -> dict[str, str]:
    text = read(DEPLOYMENT)
    blocked = not statuses.get("10.8", False) or "Task 10.8 is **blocked" in text
    return result(
        "external_player_portability",
        "BLOCKED" if blocked else "PASS",
        "scripts/check_external_audio_players.sh and DEPLOYMENT.md task 10.8",
        "two external Android audio players are unavailable; no portable playback evidence exists" if blocked else "two external players are recorded as qualified",
    )


def check_device_qualification(statuses: dict[str, bool]) -> dict[str, str]:
    text = read(QUALIFICATION)
    blocked_targets = all(f"| {target} | Blocked" in text for target in (
        "Android 11 (API 30)",
        "Android 16 (API 36)",
        "Poco F3 vendor battery-management configuration",
    ))
    blocked = not statuses.get("11.4", False) or blocked_targets
    return result(
        "device_qualification",
        "BLOCKED" if blocked else "PASS",
        "reports/task-11-4-android-qualification.md",
        "Android 11/current/Android 16/Poco sustained matrix is incomplete" if blocked else "required device matrix is qualified",
    )


def check_capability_audit(statuses: dict[str, bool]) -> dict[str, str]:
    text = read(CAPABILITY_AUDIT)
    blocked = not statuses.get("11.8", False) or "Release-candidate decision | No" in text
    return result(
        "capability_release_audit",
        "BLOCKED" if blocked else "PASS",
        "reports/task-11-8-mvp-capability-matrix.md",
        "task 11.8 audit says not to declare an MVP release candidate" if blocked else "capability audit approves the release candidate",
    )


def check_dependency_privacy_fdroid(statuses: dict[str, bool], execute: bool) -> dict[str, str]:
    missing = task_requirements(statuses, ["11.2", "11.7", "12.5", "12.6"])
    if missing:
        return result("dependency_privacy_fdroid", "BLOCKED", "task status and release documentation", missing)
    if execute:
        checks = [
            command_result([sys.executable, str(RELEASE_DOCS_CHECK)], "release_docs", "scripts/validate_release_docs.py"),
            command_result([sys.executable, str(FDROID_CHECK), "--require-build"], "fdroid", "scripts/check_fdroid.py --require-build"),
        ]
        failed = [check for check in checks if check["status"] != "PASS"]
        if failed:
            return result("dependency_privacy_fdroid", "FAIL", "release-docs and F-Droid checks", "; ".join(check["reason"] for check in failed))
    return result(
        "dependency_privacy_fdroid",
        "PASS",
        "task 11.2/11.7/12.5/12.6 evidence and static checks",
        "dependency, privacy, notices, and F-Droid substitute checks pass; no external scanner result is claimed",
    )


def check_recovery_export_instrumentation(statuses: dict[str, bool]) -> dict[str, str]:
    required = ["8.9", "10.5", "10.6", "10.7", "12.2"]
    missing = task_requirements(statuses, required)
    if missing:
        return result("recovery_export_instrumentation", "BLOCKED", "task status", missing)
    return result(
        "recovery_export_instrumentation",
        "PASS",
        "task 8.9, 10.5-10.7, and 12.2 evidence in tasks.md and capability audit",
        "recovery, export failure/isolation, and instrumentation evidence is recorded",
    )


def check_openspec(execute: bool) -> dict[str, str]:
    if execute:
        return command_result(
            ["openspec", "validate", "build-serbian-audiobook-mvp", "--strict"],
            "openspec_strict_validation",
            "openspec validate build-serbian-audiobook-mvp --strict",
        )
    text = read(CAPABILITY_AUDIT)
    passed = "OpenSpec strict validation | Pass" in text
    return result(
        "openspec_strict_validation",
        "PASS" if passed else "FAIL",
        "reports/task-11-8-mvp-capability-matrix.md",
        "recorded strict validation passed" if passed else "no passing strict validation evidence",
    )


def check_artifact_separation(artifact_dir: Path | None) -> dict[str, str]:
    source = read(RELEASE_ARTIFACTS)
    separated = all(marker in source for marker in (
        '"model_packages_included": False',
        '"audio_artifacts_included": False',
        "model packages must be separate",
    ))
    if artifact_dir and artifact_dir.is_dir():
        names = {path.name for path in artifact_dir.iterdir() if path.is_file()}
        separated = separated and not any(name.endswith((".onnx", ".pt", ".pth", ".wav", ".m4a", ".zip")) for name in names)
    return result(
        "app_artifact_separation",
        "PASS" if separated else "FAIL",
        "scripts/release_artifacts.py and app-only release contract",
        "app artifacts exclude optional model packages, generated audio, and secrets" if separated else "app artifact separation contract is incomplete",
    )


def evaluate(artifact_dir: Path | None = None, model_manifest: Path | None = None, android_report: Path | None = None, execute: bool = True) -> dict[str, object]:
    statuses = task_status()
    hard = [
        check_signed_artifacts(artifact_dir, statuses),
        check_model_legal(model_manifest),
        *check_parity(android_report),
        check_production_model_proof(),
        check_external_players(statuses),
        check_device_qualification(statuses),
        check_capability_audit(statuses),
        check_dependency_privacy_fdroid(statuses, execute),
        check_recovery_export_instrumentation(statuses),
        check_artifact_separation(artifact_dir),
        check_openspec(execute),
    ]
    informational = [result(
        "performance_measurements",
        "INFO",
        "reports/release-docs/benchmark-report.md",
        "historical RTF/memory/thermal measurements are informational and have no acceptance threshold",
    )]
    return {
        "schema": "citac-knjiga-release-gate",
        "version": 1,
        "publication": "allowed" if all(check["status"] == "PASS" for check in hard) else "refused",
        "hard_gates": hard,
        "informational": informational,
        "task_12_8": "unchecked",
        "side_effects": "read-only; does not build, sign, upload, or publish",
    }


def print_report(report: dict[str, object]) -> None:
    print(f"publication={str(report['publication']).upper()}")
    for group_name in ("hard_gates", "informational"):
        print(f"{group_name}:")
        for check in report[group_name]:
            print(f"  {check['status']}: {check['name']} - {check['reason']}")
    if report["publication"] != "allowed":
        print("Publication refused: every hard gate must be PASS; task 12.8 remains unchecked.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", type=Path, help="external signed app-only artifact directory")
    parser.add_argument("--model-manifest", type=Path, help="external model manifest to evaluate for legal clearance")
    parser.add_argument("--android-parity-report", type=Path, help="external passing Android parity report")
    parser.add_argument("--json", type=Path, help="write the machine-readable result to an external path")
    parser.add_argument("--no-execute", action="store_true", help="use recorded evidence without running read-only static checks")
    args = parser.parse_args()
    try:
        report = evaluate(args.artifact_dir, args.model_manifest, args.android_parity_report, not args.no_execute)
        print_report(report)
        if args.json:
            args.json.expanduser().resolve().write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return 0 if report["publication"] == "allowed" else 1
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"release gate evaluation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
