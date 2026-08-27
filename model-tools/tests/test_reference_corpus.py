from __future__ import annotations

import hashlib
import json
from pathlib import Path

import soundfile as sf


REPO = Path(__file__).resolve().parents[2]
CORPUS_PATH = REPO / "model-tools/reference/vectors.json"

REQUIRED_CATEGORIES = {
    "latin-cyrillic-equivalence",
    "diacritics",
    "digraphs",
    "mixed-scripts",
    "foreign-names",
    "abbreviations",
    "numbers",
    "dates",
    "currencies",
    "measurements",
    "roman-numerals",
    "punctuation",
    "urls",
    "email",
    "citations",
    "page-artifacts",
}

VECTOR_FIELDS = {
    "id",
    "script",
    "text",
    "categories",
    "equivalence_group",
    "ipa",
    "ipa_len",
    "voice_row_index",
    "token_ids",
    "token_count",
    "audio_samples",
    "duration_s",
    "peak",
    "rms",
    "finite",
    "wav",
    "infer_seconds",
}


def _input_manifest(document: dict) -> list[dict]:
    return [
        {
            key: vector[key]
            for key in ("id", "script", "text", "categories", "equivalence_group")
            if key in vector
        }
        for vector in document["vectors"]
    ]


def _input_manifest_sha256(document: dict) -> str:
    encoded = json.dumps(
        _input_manifest(document),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def test_corpus_covers_every_task_3_3_category() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    corpus = document["corpus"]
    coverage = corpus["coverage"]
    vectors = document["vectors"]
    by_id = {vector["id"]: vector for vector in vectors}

    assert len(vectors) == 22
    assert set(coverage) == REQUIRED_CATEGORIES
    assert all(coverage[category] for category in REQUIRED_CATEGORIES)
    assert len(by_id) == len(vectors)
    assert set(by_id) == {
        vector_id for vector_ids in coverage.values() for vector_id in vector_ids
    }

    for vector in vectors:
        assert set(vector) <= VECTOR_FIELDS
        assert set(vector["categories"]) <= REQUIRED_CATEGORIES
        assert vector["categories"]
        for category in vector["categories"]:
            assert vector["id"] in coverage[category]
        assert vector["ipa_len"] == len(vector["ipa"])
        assert vector["token_count"] == len(vector["token_ids"])
        assert vector["token_ids"][0] == vector["token_ids"][-1] == 0
        assert vector["voice_row_index"] == min(vector["ipa_len"], 509)
        assert vector["ipa_len"] <= document["max_input_symbols"]
        assert vector["finite"] is True

        audio, sample_rate = sf.read(vector["wav"], dtype="float32")
        assert sample_rate == document["sample_rate"]
        assert audio.size == vector["audio_samples"]


def test_equivalence_groups_have_exact_reference_outputs() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    by_id = {vector["id"]: vector for vector in document["vectors"]}

    for group in document["corpus"]["equivalence_groups"].values():
        vectors = [by_id[vector_id] for vector_id in group["vector_ids"]]
        assert {vector["script"] for vector in vectors} == {"latin", "cyrillic"}
        assert len({vector["ipa"] for vector in vectors}) == 1
        assert len({tuple(vector["token_ids"]) for vector in vectors}) == 1


def test_script_and_digraph_cases_contain_the_declared_forms() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    by_id = {vector["id"]: vector for vector in document["vectors"]}

    assert {"č", "ć", "š", "ž", "đ"} <= set(
        by_id["diacritics-latin"]["text"].lower()
    )
    digraph_text = by_id["digraphs-latin"]["text"].lower()
    assert all(digraph in digraph_text for digraph in ("lj", "nj", "dž"))
    mixed_text = by_id["mixed-scripts"]["text"]
    assert any("A" <= character <= "z" for character in mixed_text)
    assert any("\u0400" <= character <= "\u04ff" for character in mixed_text)


def test_corpus_provenance_and_regeneration_contract_is_pinned() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    corpus = document["corpus"]
    provenance = corpus["input_provenance"]
    generation = corpus["generation"]
    runtime = generation["reference_runtime"]

    assert corpus["version"] == "reference-20260827-task-3.3"
    assert provenance["kind"] == "self-authored"
    assert provenance["license_status"] == "project-owned"
    assert "third-party" in provenance["statement"]
    assert generation["script"] == "model-tools/scripts/capture_reference_vectors.py"
    assert generation["seed"] == document["seed"] == 20260826
    assert generation["torch_num_threads"] == document["torch_num_threads"] == 1
    assert generation["input_manifest_sha256"] == _input_manifest_sha256(document)
    assert generation["non_deterministic_measurements"] == ["infer_seconds"]
    assert runtime["kokoro_revision"] == "b96fef95e6a746495f92443fac7c688f90fc57fc"
    assert runtime["kokoro_sr_revision"] == "ca5590d9576f63b0763e51a73de0596d47f05425"
    assert runtime["espeak_ng_version"] == "1.52.0"
    assert runtime["espeak_ng_command"] == "espeak-ng -q --ipa=3 -v sr --stdin"
