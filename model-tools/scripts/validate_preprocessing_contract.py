#!/usr/bin/env python3
"""Validate the checked-in Serbian preprocessing resources and contract.

This validator checks resource bytes and contract identity. It deliberately does
not run eSpeak-NG, compare golden vectors, or claim Android qualification; those
are later compatibility gates.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


REPO = Path(__file__).resolve().parents[2]
PREPROCESSING = REPO / "model-tools/preprocessing"
CONTRACT_PATH = PREPROCESSING / "preprocessing-contract-v1.json"
SCHEMA_PATH = PREPROCESSING / "preprocessing-contract-v1.schema.json"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: top-level value must be an object")
    return value


def canonical_identity_bytes(contract: dict[str, Any]) -> bytes:
    projection = {
        "contract_id": contract["contract_id"],
        "contract_version": contract["contract_version"],
        "resources": sorted(
            [
                {"path": item["path"], "sha256": item["sha256"]}
                for item in contract["resources"]
            ],
            key=lambda item: (item["path"], item["sha256"]),
        ),
    }
    return json.dumps(
        projection,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def expected_identity(contract: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_identity_bytes(contract)).hexdigest()


def validate_contract(contract_path: Path = CONTRACT_PATH) -> dict[str, Any]:
    contract = load_json(contract_path)
    schema = load_json(SCHEMA_PATH)
    Draft202012Validator.check_schema(schema)
    errors = sorted(
        Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(contract),
        key=lambda error: list(error.path),
    )
    if errors:
        details = [
            f"{'.'.join(str(part) for part in error.path) or '<root>'}: {error.message}"
            for error in errors
        ]
        raise ValueError("; ".join(details))

    if contract["identity"]["value"] != expected_identity(contract):
        raise ValueError("identity.value does not match the canonical resource identity")

    resource_ids = {item["resource_id"] for item in contract["resources"]}
    if resource_ids != {"vocabulary", "normalization", "chunking"}:
        raise ValueError("resources must contain exactly vocabulary, normalization, and chunking")

    for resource in contract["resources"]:
        path = REPO / resource["path"]
        if not path.is_file():
            raise ValueError(f"{resource['resource_id']}: resource does not exist: {resource['path']}")
        resource_bytes = path.read_bytes()
        try:
            resource_value = json.loads(resource_bytes.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"{resource['resource_id']}: resource is not UTF-8 JSON") from exc
        expected_serialization = (
            json.dumps(resource_value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
        ).encode("utf-8")
        if resource_bytes != expected_serialization:
            raise ValueError(
                f"{resource['resource_id']}: resource is not json-sorted-keys-utf8-v1"
            )
        actual = hashlib.sha256(resource_bytes).hexdigest()
        if actual != resource["sha256"]:
            raise ValueError(
                f"{resource['resource_id']}: checksum mismatch: expected {resource['sha256']}, got {actual}"
            )

    vocabulary = load_json(REPO / "model-tools/preprocessing/vocabulary-v1.json")
    entries = vocabulary["entries"]
    if (
        len(entries) != vocabulary["model_config_entry_count"]
        or vocabulary["valid_symbol_count"] != 115
        or vocabulary["size"] != 178
    ):
        raise ValueError("vocabulary resource does not match the 178-slot model contract")
    if vocabulary["tokenization"]["boundary_token_id"] != 0:
        raise ValueError("vocabulary boundary token must be 0")
    if vocabulary["tokenization"]["unknown_symbol_policy"] != "reject":
        raise ValueError("vocabulary unknown-symbol policy must be reject")
    if any(not isinstance(token_id, int) or not 0 < token_id < 178 for token_id in entries.values()):
        raise ValueError("vocabulary token IDs must be integer slots from 1 through 177")
    if len(set(entries.values())) != len(entries):
        raise ValueError("vocabulary token IDs must be unique")

    chunking = load_json(REPO / "model-tools/preprocessing/chunking-v1.json")
    limits = chunking["limits"]
    if limits != {
        "hard_phoneme_symbols": 510,
        "operational_phoneme_symbols": 507,
        "model_sequence_includes_boundary_tokens": True,
        "model_max_sequence_length": 512,
    }:
        raise ValueError("chunking limits do not match the verified model contract")

    return contract


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("contract", type=Path, default=CONTRACT_PATH, nargs="?")
    args = parser.parse_args()
    try:
        contract = validate_contract(args.contract)
    except (OSError, ValueError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1
    print(json.dumps({
        "ok": True,
        "contract_id": contract["contract_id"],
        "contract_version": contract["contract_version"],
        "identity_sha256": expected_identity(contract),
        "resource_count": len(contract["resources"]),
        "portable_resources": contract["status"]["portable_resources"],
        "android_compatibility": contract["status"]["android_compatibility"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
