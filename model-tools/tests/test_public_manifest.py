from __future__ import annotations

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from package_manifest import EXAMPLE_PATH, load_and_validate  # noqa: E402
from scripts.prepare_public_manifest import JUZNE_VESTI_URL, prepare_manifest  # noqa: E402


def test_public_manifest_is_generated_from_payload_without_mutating_blocked_example(tmp_path: Path) -> None:
    before = EXAMPLE_PATH.read_bytes()
    source = load_and_validate(EXAMPLE_PATH)
    payload_root = tmp_path / "payload"
    for artifact in source["artifacts"]:
        path = payload_root.joinpath(*artifact["path"].split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(artifact["artifact_id"].encode("ascii"))

    manifest = prepare_manifest(payload_root, created_at="2026-08-28T00:00:00Z")

    assert EXAMPLE_PATH.read_bytes() == before
    assert manifest["legal"]["status"] == "cleared"
    assert manifest["legal"]["package_distribution"] == "public"
    assert manifest["legal"]["outstanding_reviews"] == []
    assert manifest["attribution"][1]["source_url"] == JUZNE_VESTI_URL
    assert all(artifact["distribution_status"] == "allowed" for artifact in manifest["artifacts"])
