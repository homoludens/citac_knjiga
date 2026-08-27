from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.validate_preprocessing_contract import (  # noqa: E402
    CONTRACT_PATH,
    expected_identity,
    load_json,
    validate_contract,
)


def test_contract_and_resources_are_checksum_pinned() -> None:
    contract = validate_contract()

    assert contract["identity"]["value"] == expected_identity(contract)
    assert contract["status"]["portable_resources"] == "checked_in_and_checksum_pinned"
    assert contract["status"]["android_compatibility"] == "not_yet_qualified"
    assert contract["phonemizer"]["version"] == "1.52.0"
    assert contract["phonemizer"]["source"]["status"] == (
        "recorded_for_reproducible_native_build"
    )
    assert contract["phonemizer"]["android_candidate"]["status"] == (
        "implemented_not_qualified"
    )
    assert contract["phonemizer"]["reference_installation"]["data_closure_status"] == (
        "incomplete_investigation_fingerprint"
    )
    assert contract["compatibility"]["vocabulary"]["model_config_entry_count"] == 114


def test_contract_rejects_resource_checksum_change() -> None:
    contract = deepcopy(load_json(CONTRACT_PATH))
    contract["resources"][0]["sha256"] = "0" * 64

    with pytest.raises(ValueError, match="identity.value"):
        validate_contract_with_value(contract)


def validate_contract_with_value(contract: dict) -> None:
    from jsonschema import Draft202012Validator, FormatChecker

    schema = load_json(CONTRACT_PATH.with_name("preprocessing-contract-v1.schema.json"))
    errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(contract))
    assert not errors
    if contract["identity"]["value"] != expected_identity(contract):
        raise ValueError("identity.value does not match the canonical resource identity")
