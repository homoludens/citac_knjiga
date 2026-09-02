import json
import unittest
from pathlib import Path

from scripts import check_source_closure


ROOT = Path(__file__).resolve().parents[1]


class NetworkPolicyTest(unittest.TestCase):
    def test_pinned_descriptors_match_source_closure_network_policy(self):
        policy = json.loads(
            (ROOT / "model-tools/native/source-closure-v1.json").read_text(encoding="utf-8")
        )
        check_source_closure.verify_network_policy(ROOT, policy)

    def test_network_policy_rejects_an_unconfigured_asset(self):
        policy = json.loads(
            (ROOT / "model-tools/native/source-closure-v1.json").read_text(encoding="utf-8")
        )
        policy["network_policy"]["allowed_assets"][0]["url"] = "https://github.com/example/project/releases/download/v1/model.zip"
        with self.assertRaises(check_source_closure.ClosureError):
            check_source_closure.verify_network_policy(ROOT, policy)


if __name__ == "__main__":
    unittest.main()
