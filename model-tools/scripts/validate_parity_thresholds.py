#!/usr/bin/env python3
"""Validate the versioned FP32 parity-threshold declaration (task 2.4)."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "model-tools"))

from parity.thresholds import DEFAULT_THRESHOLDS_PATH, load_thresholds  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--thresholds", type=Path, default=DEFAULT_THRESHOLDS_PATH)
    args = parser.parse_args()
    try:
        declaration = load_thresholds(args.thresholds)
    except ValueError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1
    print(json.dumps({
        "ok": True,
        "path": str(args.thresholds),
        "thresholds_version": declaration["thresholds_version"],
        "schema_version": declaration["schema_version"],
        "metrics": sorted(declaration["metrics"]),
        "runtime_override_allowed": declaration["policy"]["runtime_override_allowed"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
