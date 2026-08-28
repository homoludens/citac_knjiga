#!/usr/bin/env python3
"""Populate a public v1 model manifest from an exact local payload tree.

The blocked example manifest remains a negative-test fixture. This command
copies it, replaces every artifact size/checksum from the supplied payload,
and applies the project-confirmed public derived-package declaration.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "model-tools"))

from package_builder import sha256_file  # noqa: E402
from package_manifest import EXAMPLE_PATH, expected_identity, load_and_validate, validate_manifest  # noqa: E402


PUBLIC_CLEARANCE_RECORD = (
    "Project owner confirmation recorded 2026-08-28: the derived model, ONNX, "
    "and voice package may be used and publicly distributed as CC BY-SA 4.0 "
    "with required attribution. This records project input and is not legal advice."
)
JUZNE_VESTI_URL = "https://www.clarin.si/repository/xmlui/handle/11356/1679"


def _payload_files(payload_root: Path) -> dict[str, Path]:
    if not payload_root.is_dir():
        raise ValueError(f"payload root is not a directory: {payload_root}")
    files: dict[str, Path] = {}
    for path in sorted(payload_root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_symlink():
            raise ValueError(f"payload symlinks are not allowed: {path}")
        if path.is_file():
            files[path.relative_to(payload_root).as_posix()] = path
    return files


def prepare_manifest(payload_root: Path, *, created_at: str) -> dict:
    manifest = deepcopy(load_and_validate(EXAMPLE_PATH))
    files = _payload_files(payload_root)
    declared_paths = {artifact["path"] for artifact in manifest["artifacts"]}
    undeclared = sorted(set(files) - declared_paths)
    missing = sorted(declared_paths - set(files))
    if undeclared:
        raise ValueError("undeclared payload files: " + ", ".join(undeclared))
    if missing:
        raise ValueError("missing declared payload files: " + ", ".join(missing))

    for artifact in manifest["artifacts"]:
        payload = files[artifact["path"]]
        artifact["size_bytes"] = payload.stat().st_size
        artifact["sha256"] = sha256_file(payload)
        artifact["distribution_status"] = "allowed"

    manifest["manifest"]["created_at"] = created_at
    manifest["manifest"]["identity"]["value"] = expected_identity(manifest)
    manifest["licenses"][1]["terms_status"] = "declared"
    manifest["licenses"][1]["modification_note"] = (
        "JuzneVesti-SR-derived model, voice, and test audio are treated as CC BY-SA 4.0 "
        "for public distribution, with attribution and ShareAlike requirements."
    )
    for attribution in manifest["attribution"]:
        if attribution["id"] == "attribution-juzne-vesti":
            attribution["source_url"] = JUZNE_VESTI_URL
            attribution["text"] = (
                "JuzneVesti-SR v1.0 by Peter Rupnik and Nikola Ljubešić, "
                "Jožef Stefan Institute / CLARIN.SI. CC BY-SA 4.0. "
                "Modifications: the corpus is used in the derived model and voice package."
            )
    manifest["legal"].update({
        "status": "cleared",
        "model_distribution": "allowed",
        "package_distribution": "public",
        "blocked_artifact_ids": [],
        "outstanding_reviews": [],
        "clearance_evidence": [PUBLIC_CLEARANCE_RECORD],
    })
    validate_manifest(manifest)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--created-at",
        required=True,
        help="RFC 3339 timestamp recorded in the manifest; pass a fixed value for reproducibility",
    )
    args = parser.parse_args()
    try:
        datetime.fromisoformat(args.created_at.replace("Z", "+00:00"))
        manifest = prepare_manifest(args.payload_root, created_at=args.created_at)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1
    print(json.dumps({
        "ok": True,
        "manifest": str(args.output),
        "package_id": manifest["manifest"]["package_id"],
        "artifact_count": len(manifest["artifacts"]),
        "identity_sha256": manifest["manifest"]["identity"]["value"],
        "legal_status": manifest["legal"]["status"],
        "package_distribution": manifest["legal"]["package_distribution"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
