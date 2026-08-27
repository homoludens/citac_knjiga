"""Validation for the v1 Serbian model-package manifest.

This is a declaration validator only. It does not read payload files, build an
archive, install a package, or decide whether any artifact is legally cleared.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


REPO = Path(__file__).resolve().parents[1]
SCHEMA_PATH = REPO / "model-tools/package/model-package-v1.schema.json"
EXAMPLE_PATH = REPO / "model-tools/package/model-package-v1.example.json"


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path}: top-level value must be an object")
    return value


def canonical_identity_bytes(manifest: dict[str, Any]) -> bytes:
    artifacts = [
        {"path": item["path"], "sha256": item["sha256"]}
        for item in manifest["artifacts"]
    ]
    projection = {
        "package_id": manifest["manifest"]["package_id"],
        "package_version": manifest["manifest"]["package_version"],
        "artifacts": sorted(artifacts, key=lambda item: (item["path"], item["sha256"])),
    }
    return json.dumps(
        projection,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def expected_identity(manifest: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_identity_bytes(manifest)).hexdigest()


def _semantic_errors(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    artifacts = manifest["artifacts"]
    artifact_by_id = {item["artifact_id"]: item for item in artifacts}
    licenses = manifest["licenses"]
    license_by_id = {item["id"]: item for item in licenses}
    attribution_by_id = {item["id"]: item for item in manifest["attribution"]}

    if len(artifact_by_id) != len(artifacts):
        errors.append("artifacts.artifact_id values must be unique")
    if len({item["path"] for item in artifacts}) != len(artifacts):
        errors.append("artifacts.path values must be unique")
    if manifest["manifest"]["manifest_path"] in {item["path"] for item in artifacts}:
        errors.append("manifest_path must not also be a payload artifact path")

    for artifact in artifacts:
        for ref in artifact["license_refs"]:
            if ref not in license_by_id:
                errors.append(f"{artifact['artifact_id']}: unknown license reference {ref}")
        for ref in artifact["attribution_refs"]:
            if ref not in attribution_by_id:
                errors.append(f"{artifact['artifact_id']}: unknown attribution reference {ref}")

    for license_entry in licenses:
        for ref in license_entry["applies_to"]:
            if ref not in artifact_by_id:
                errors.append(f"{license_entry['id']}: unknown artifact reference {ref}")
    for attribution in manifest["attribution"]:
        if attribution["license_id"] not in license_by_id:
            errors.append(f"{attribution['id']}: unknown license reference {attribution['license_id']}")

    def require_artifact(section: str, key: str, role: str, nested_key: str | None = None) -> None:
        reference = manifest[section][key]
        artifact_id = reference[nested_key] if nested_key else reference
        artifact = artifact_by_id.get(artifact_id)
        if artifact is None:
            errors.append(f"{section}.{key}: unknown artifact reference {artifact_id}")
        elif role not in artifact["roles"]:
            errors.append(f"{section}.{key}: artifact {artifact_id} lacks role {role}")

    require_artifact("model", "artifact_id", "model")
    require_artifact("model", "architecture", "configuration", "config_artifact_id")
    require_artifact("voice_style", "artifact_id", "voice_style")
    require_artifact("vocabulary", "artifact_id", "vocabulary")
    require_artifact("configuration", "artifact_id", "configuration")
    require_artifact("test_vectors", "manifest_artifact_id", "test_vector")
    for vector in manifest["test_vectors"]["vectors"]:
        audio = artifact_by_id.get(vector["audio_artifact_id"])
        if audio is None:
            errors.append(f"test vector {vector['id']}: unknown audio artifact")
        elif "test_audio" not in audio["roles"]:
            errors.append(f"test vector {vector['id']}: audio artifact lacks test_audio role")

    legal = manifest["legal"]
    model_artifacts = [
        item for item in artifacts if {"model", "voice_style"} & set(item["roles"])
    ]
    if legal["model_distribution"] != "allowed":
        for artifact in model_artifacts:
            if artifact["distribution_status"] == "allowed":
                errors.append(
                    f"{artifact['artifact_id']}: allowed artifact conflicts with blocked/pending legal gate"
                )
            if legal["model_distribution"] == "blocked":
                if artifact["distribution_status"] != "blocked":
                    errors.append(
                        f"{artifact['artifact_id']}: blocked model gate requires blocked distribution status"
                    )
                if artifact["artifact_id"] not in legal["blocked_artifact_ids"]:
                    errors.append(
                        f"{artifact['artifact_id']}: blocked model artifact must be listed in legal.blocked_artifact_ids"
                    )
    else:
        for artifact in model_artifacts:
            if artifact["distribution_status"] != "allowed":
                errors.append(
                    f"{artifact['artifact_id']}: cleared model gate requires allowed distribution status"
                )
    if legal["status"] != "cleared" and legal["model_distribution"] == "allowed":
        errors.append("legal.model_distribution=allowed requires legal.status=cleared")
    for artifact_id in legal["blocked_artifact_ids"]:
        if artifact_id not in artifact_by_id:
            errors.append(f"legal.blocked_artifact_ids: unknown artifact {artifact_id}")

    identity = manifest["manifest"]["identity"]
    if identity["value"] != expected_identity(manifest):
        errors.append("manifest.identity.value does not match the canonical artifact identity")
    return errors


def validate_manifest(manifest: dict[str, Any]) -> None:
    schema = load_json(SCHEMA_PATH)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    schema_errors = sorted(validator.iter_errors(manifest), key=lambda error: list(error.path))
    if schema_errors:
        messages = [
            f"{'.'.join(str(part) for part in error.path) or '<root>'}: {error.message}"
            for error in schema_errors
        ]
        raise ValueError("; ".join(messages))
    semantic_errors = _semantic_errors(manifest)
    if semantic_errors:
        raise ValueError("; ".join(semantic_errors))


def load_and_validate(path: Path = EXAMPLE_PATH) -> dict[str, Any]:
    manifest = load_json(path)
    validate_manifest(manifest)
    return manifest
