#!/usr/bin/env python3
"""Build and verify a deterministic Serbian model-package v1 archive.

The payload root must contain exactly the package-relative paths declared by
the manifest. The manifest itself is supplied separately and is written to the
archive at its declared manifest path. A package is never published while the
legal gate is blocked or while any declared byte fails its checksum or size
check.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "model-tools"))

from package_builder import PackageError, build_package, validate_package  # noqa: E402
from package_manifest import EXAMPLE_PATH  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=EXAMPLE_PATH)
    parser.add_argument("--payload-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        package_path = build_package(args.manifest, args.payload_root, args.output)
        manifest = validate_package(package_path)
    except (OSError, PackageError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1

    print(json.dumps({
        "ok": True,
        "package": str(package_path),
        "package_id": manifest["manifest"]["package_id"],
        "package_version": manifest["manifest"]["package_version"],
        "artifact_count": len(manifest["artifacts"]),
        "legal_status": manifest["legal"]["status"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
