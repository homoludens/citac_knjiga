#!/usr/bin/env python3
"""Validate trusted publisher and legal evidence for advertising a model release."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


TRUSTED_RECORDERS = {"release-tooling", "legal-review"}


def validate(metadata: dict[str, object]) -> None:
    if metadata.get("schema") != "citac-knjiga-model-release-gate" or metadata.get("version") != 1:
        raise ValueError("unsupported release-gate metadata")
    publisher = metadata.get("publisher_authentication")
    legal = metadata.get("legal_clearance")
    if not isinstance(publisher, dict) or not isinstance(legal, dict):
        raise ValueError("trusted publisher authentication and legal clearance are required")
    for section, value in (("publisher_authentication", publisher), ("legal_clearance", legal)):
        if value.get("trusted") is not True:
            raise ValueError(f"{section} is not trusted recorded evidence")
        if not isinstance(value.get("record_id"), str) or not value["record_id"].strip():
            raise ValueError(f"{section} requires a recorded evidence ID")
        if value.get("recorded_by") not in TRUSTED_RECORDERS:
            raise ValueError(f"{section} has no trusted recorder")
    if not isinstance(metadata.get("release_url"), str) or not metadata["release_url"].strip():
        raise ValueError("release URL is required")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    args = parser.parse_args()
    try:
        value = json.loads(args.metadata.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("metadata must be an object")
        validate(value)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"model release gate failed: {error}")
        return 1
    print("model release gate passed: trusted publisher and legal records present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
