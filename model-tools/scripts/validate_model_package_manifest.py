#!/usr/bin/env python3
"""Validate a v1 model-package manifest declaration."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "model-tools"))

from package_manifest import EXAMPLE_PATH, expected_identity, load_and_validate  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, default=EXAMPLE_PATH, nargs="?")
    args = parser.parse_args()
    try:
        manifest = load_and_validate(args.manifest)
    except (OSError, ValueError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1
    print(json.dumps({
        "ok": True,
        "schema": manifest["schema"],
        "package_id": manifest["manifest"]["package_id"],
        "package_version": manifest["manifest"]["package_version"],
        "artifact_count": len(manifest["artifacts"]),
        "identity_sha256": expected_identity(manifest),
        "legal_status": manifest["legal"]["status"],
        "legal_clearance": "not_evaluated",
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
