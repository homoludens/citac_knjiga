import json
import tempfile
import unittest
from pathlib import Path

from qualify import generated_fixtures, report


class QualificationTest(unittest.TestCase):
    def test_every_fixture_is_loaded_from_local_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            checksums = generated_fixtures(Path(directory))
        self.assertEqual(13, len(checksums))
        self.assertTrue(all(len(value) == 64 for value in checksums.values()))

    def test_host_report_is_non_gating_without_android_execution(self):
        result = report(Path("/tmp/qualification-report.json"))
        self.assertEqual("no-pass", result["qualification"])
        self.assertIsNone(result["selected_candidate"])
        self.assertFalse(result["production_pdf_enabled"])
        self.assertEqual({"30", "33", "35", "36"}, set(result["matrix"]["androidx-pdf"]))
        self.assertEqual("33", result["qualification_scope"]["production"]["api"])
        self.assertEqual("arm64-v8a", result["qualification_scope"]["production"]["abi"])
        self.assertEqual(["30", "36"], result["qualification_scope"]["non_gating"])


if __name__ == "__main__":
    unittest.main()
