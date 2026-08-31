#!/usr/bin/env python3
"""Check links and hashes in the redacted Serbian VITS evidence bundle."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def check(report_dir: Path) -> int:
    manifest = json.loads((report_dir / "qualification-manifest.json").read_text(encoding="utf-8"))
    errors: list[str] = []
    for relative, expected in manifest["evidence_hashes"].items():
        path = report_dir / relative
        if not path.is_file():
            errors.append(f"missing evidence: {relative}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            errors.append(f"evidence checksum mismatch: {relative}")
    summary = json.loads((report_dir / "gate-summary.json").read_text(encoding="utf-8"))
    for gate in summary["gates"]:
        for link in gate["evidence"]:
            if not (report_dir / link).is_file():
                errors.append(f"missing gate evidence: {link}")
    if summary.get("outcome") not in {"ACCEPTED", "REJECTED"}:
        errors.append("summary outcome is not explicit")
    if errors:
        raise ValueError("; ".join(errors))
    print(f"evidence integrity verified: {len(manifest['evidence_hashes'])} hashes, {len(summary['gates'])} gates")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, default=Path(__file__).resolve().parents[2] / "reports/serbian-vits-qualification")
    args = parser.parse_args()
    try:
        return check(args.reports)
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"evidence integrity failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
