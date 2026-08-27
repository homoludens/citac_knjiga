from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from package_manifest import EXAMPLE_PATH, expected_identity, load_and_validate, load_json, validate_manifest


def test_v1_example_is_valid_and_fail_closed_for_legal_status() -> None:
    manifest = load_and_validate()

    assert manifest["schema"] == {"id": "serbian-model-package", "version": 1}
    assert manifest["legal"]["status"] == "blocked"
    assert manifest["legal"]["model_distribution"] == "blocked"
    assert manifest["preprocessing"]["android_status"] == "not-yet-qualified"
    assert manifest["runtime"]["qualification_status"] == "desktop-parity-only"
    assert manifest["test_vectors"]["all_required"] is True
    assert len(manifest["test_vectors"]["vectors"]) == 22
    assert manifest["manifest"]["identity"]["value"] == expected_identity(manifest)
    assert EXAMPLE_PATH.is_file()


def test_validator_rejects_missing_required_artifact_reference() -> None:
    manifest = load_and_validate()
    malformed = deepcopy(manifest)
    malformed["voice_style"]["artifact_id"] = "missing-voice"

    with pytest.raises(ValueError, match="unknown artifact reference missing-voice"):
        validate_manifest(malformed)


def test_validator_rejects_legal_clearance_without_evidence_or_allowed_artifacts() -> None:
    manifest = load_and_validate()
    malformed = deepcopy(manifest)
    malformed["legal"]["status"] = "cleared"
    malformed["legal"]["model_distribution"] = "allowed"

    with pytest.raises(ValueError, match="clearance_evidence|minItems|requires allowed distribution"):
        validate_manifest(malformed)


def test_validator_rejects_allowed_model_artifact_while_gate_is_blocked() -> None:
    manifest = load_and_validate()
    malformed = deepcopy(manifest)
    malformed["artifacts"][0]["distribution_status"] = "allowed"

    with pytest.raises(ValueError, match="allowed artifact conflicts"):
        validate_manifest(malformed)


def test_validator_rejects_unlisted_model_artifact_while_gate_is_blocked() -> None:
    manifest = load_and_validate()
    malformed = deepcopy(manifest)
    malformed["legal"]["blocked_artifact_ids"].remove("model-onnx")

    with pytest.raises(ValueError, match="must be listed in legal.blocked_artifact_ids"):
        validate_manifest(malformed)


def test_validator_rejects_identity_changes() -> None:
    manifest = load_and_validate()
    malformed = deepcopy(manifest)
    malformed["artifacts"][0]["sha256"] = "0" * 64

    with pytest.raises(ValueError, match="canonical artifact identity"):
        validate_manifest(malformed)


def test_schema_rejects_unknown_core_fields_but_extension_namespace_is_available() -> None:
    manifest = load_json(EXAMPLE_PATH)
    malformed = deepcopy(manifest)
    malformed["runtime"]["unreviewed_setting"] = True

    with pytest.raises(ValueError, match="Additional properties are not allowed"):
        validate_manifest(malformed)

    manifest["extensions"]["org.example:future-field"] = {"value": 1}
    validate_manifest(manifest)
