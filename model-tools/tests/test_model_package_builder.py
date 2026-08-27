from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from package_builder import PackageError, build_package, validate_package  # noqa: E402
from package_manifest import EXAMPLE_PATH, expected_identity, load_and_validate  # noqa: E402


def _write_fixture(tmp_path: Path, *, cleared: bool) -> tuple[Path, Path]:
    manifest = deepcopy(load_and_validate(EXAMPLE_PATH))
    payload_root = tmp_path / "payload"
    payload_root.mkdir()

    for artifact in manifest["artifacts"]:
        content = (artifact["artifact_id"] + "\n").encode("ascii")
        path = payload_root / Path(*artifact["path"].split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        artifact["sha256"] = hashlib.sha256(content).hexdigest()
        artifact["size_bytes"] = len(content)

    if cleared:
        for artifact in manifest["artifacts"]:
            artifact["distribution_status"] = "allowed"
        for license_entry in manifest["licenses"]:
            license_entry["terms_status"] = "declared"
        manifest["legal"].update({
            "status": "cleared",
            "model_distribution": "allowed",
            "package_distribution": "restricted",
            "blocked_artifact_ids": [],
            "outstanding_reviews": [],
            "clearance_evidence": ["test fixture only"],
        })
    manifest["manifest"]["identity"]["value"] = expected_identity(manifest)
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest_path, payload_root


def test_packager_rejects_undeclared_files(tmp_path: Path) -> None:
    manifest_path, payload_root = _write_fixture(tmp_path, cleared=True)
    (payload_root / "unexpected.bin").write_bytes(b"not declared")
    output = tmp_path / "package.zip"

    with pytest.raises(PackageError, match="undeclared payload files"):
        build_package(manifest_path, payload_root, output)
    assert not output.exists()


def test_packager_rejects_checksum_failure(tmp_path: Path) -> None:
    manifest_path, payload_root = _write_fixture(tmp_path, cleared=True)
    (payload_root / "config" / "config.json").write_bytes(b"X" * 14)
    output = tmp_path / "package.zip"

    with pytest.raises(PackageError, match="checksum mismatch"):
        build_package(manifest_path, payload_root, output)
    assert not output.exists()


def test_packager_output_is_deterministic(tmp_path: Path) -> None:
    manifest_path, payload_root = _write_fixture(tmp_path, cleared=True)
    first = tmp_path / "first.zip"
    second = tmp_path / "second.zip"

    build_package(manifest_path, payload_root, first)
    build_package(manifest_path, payload_root, second)

    assert first.read_bytes() == second.read_bytes()
    validate_package(first)


def test_packager_successfully_validates_completed_package(tmp_path: Path) -> None:
    manifest_path, payload_root = _write_fixture(tmp_path, cleared=True)
    output = tmp_path / "package.zip"

    build_package(manifest_path, payload_root, output)
    manifest = validate_package(output)

    assert output.is_file()
    assert len(manifest["artifacts"]) == 26


def test_packager_rejects_blocked_legal_status(tmp_path: Path) -> None:
    manifest_path, payload_root = _write_fixture(tmp_path, cleared=False)
    output = tmp_path / "package.zip"

    with pytest.raises(PackageError, match="legal gate blocks package"):
        build_package(manifest_path, payload_root, output)
    assert not output.exists()
