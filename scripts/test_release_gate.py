#!/usr/bin/env python3
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import check_release_gate


class ReleaseGateTest(unittest.TestCase):
    def test_current_evidence_refuses_publication_without_running_checks(self):
        report = check_release_gate.evaluate(execute=False)
        self.assertEqual(report["publication"], "refused")
        self.assertEqual(report["task_12_8"], "unchecked")
        statuses = {item["name"]: item["status"] for item in report["hard_gates"]}
        self.assertEqual(statuses["signed_app_artifacts"], "BLOCKED")
        self.assertEqual(statuses["model_legal_status"], "BLOCKED")
        self.assertEqual(statuses["external_player_portability"], "BLOCKED")
        self.assertEqual(statuses["device_qualification"], "BLOCKED")
        self.assertEqual(statuses["capability_release_audit"], "BLOCKED")
        self.assertEqual(report["informational"][0]["status"], "INFO")

    def test_unsigned_artifact_directory_cannot_satisfy_signed_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact_dir = Path(directory)
            (artifact_dir / "release-manifest.json").write_text(json.dumps({}), encoding="utf-8")
            with patch.object(check_release_gate, "command_result", return_value=check_release_gate.result(
                "signed_app_artifacts", "FAIL", "release_artifacts.py verify", "unsigned artifact verification failed"
            )):
                check = check_release_gate.check_signed_artifacts(artifact_dir, {"12.7": True})
        self.assertEqual(check["status"], "FAIL")

    def test_model_manifest_must_have_explicit_clearance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps({"legal": {"status": "allowed"}}), encoding="utf-8")
            check = check_release_gate.check_model_legal(path)
        self.assertEqual(check["status"], "BLOCKED")


if __name__ == "__main__":
    unittest.main()
