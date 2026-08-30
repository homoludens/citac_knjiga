from __future__ import annotations

import unittest

from scripts.validate_model_release_gate import validate


class ModelReleaseGateTest(unittest.TestCase):
    def metadata(self) -> dict[str, object]:
        evidence = {"trusted": True, "record_id": "record-1", "recorded_by": "release-tooling"}
        return {
            "schema": "citac-knjiga-model-release-gate",
            "version": 1,
            "release_url": "https://example.com/model",
            "publisher_authentication": evidence,
            "legal_clearance": {**evidence, "recorded_by": "legal-review"},
        }

    def test_requires_trusted_recorded_evidence(self) -> None:
        metadata = self.metadata()
        validate(metadata)
        metadata["publisher_authentication"] = {"publisher": "self-declared"}
        with self.assertRaises(ValueError):
            validate(metadata)

    def test_rejects_missing_or_untrusted_legal_record(self) -> None:
        for key in ("release_url", "legal_clearance"):
            metadata = self.metadata()
            if key == "release_url":
                metadata[key] = ""
            else:
                metadata[key] = {"trusted": False, "record_id": "self", "recorded_by": "publisher"}
            with self.assertRaises(ValueError):
                validate(metadata)


if __name__ == "__main__":
    unittest.main()
