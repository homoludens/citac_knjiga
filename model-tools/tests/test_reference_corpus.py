from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

import pytest
from jsonschema import Draft202012Validator, FormatChecker
import soundfile as sf


REPO = Path(__file__).resolve().parents[2]
CORPUS_PATH = REPO / "model-tools/reference/vectors.json"
SCHEMA_PATH = REPO / "model-tools/reference/vectors.schema.json"
PHONEMIZER_ROOT = Path("/home/homoludens/projekti/kokoro_tts_srpski_2/src")
VOCAB_PATH = REPO / "kokoro_sr_dragana_voice/config.json"

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
    "input-limit",
    "chunk-boundaries",
    "no-sentence-boundary",
}

VECTOR_FIELDS = {
    "id",
    "script",
    "text",
    "categories",
    "equivalence_group",
    "cleanup_text",
    "normalized_text",
    "phonemes",
    "phoneme_count",
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
    "protected_spans",
    "chunk_boundaries",
    "reference_audio",
    "infer_seconds",
}

STAGE_ORDER = (
    "cleanup_text",
    "normalized_text",
    "phonemes",
    "token_ids",
    "protected_spans",
    "chunk_boundaries",
    "reference_audio",
)


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


def _first_stage_error(vector: dict, document: dict) -> str | None:
    vector_id = vector.get("id", "<unknown>")
    for stage in STAGE_ORDER:
        if stage not in vector:
            return f"{vector_id}: missing stage {stage}"
        if stage == "cleanup_text" and vector[stage] != vector.get("text"):
            return f"{vector_id}: divergent stage cleanup_text"
        if stage == "normalized_text" and vector[stage] != vector.get("cleanup_text"):
            return f"{vector_id}: divergent stage normalized_text"
        if stage == "phonemes" and vector[stage] != vector.get("ipa"):
            return f"{vector_id}: divergent stage phonemes"
        if stage == "phonemes" and vector.get("phoneme_count") != len(vector[stage]):
            return f"{vector_id}: divergent stage phonemes"
        if stage == "token_ids":
            if vector[stage][0] != 0 or vector[stage][-1] != 0:
                return f"{vector_id}: divergent stage token_ids"
            if len(vector[stage]) != vector.get("token_count"):
                return f"{vector_id}: divergent stage token_ids"
            vocab = json.loads(VOCAB_PATH.read_text(encoding="utf-8"))["vocab"]
            try:
                expected = [0, *(vocab[character] for character in vector["phonemes"]), 0]
            except KeyError:
                return f"{vector_id}: divergent stage token_ids"
            if vector[stage] != expected:
                return f"{vector_id}: divergent stage token_ids"
        if stage == "protected_spans":
            previous_end = 0
            for span in vector[stage]:
                if not 0 <= span["start"] < span["end"] <= len(vector["normalized_text"]):
                    return f"{vector_id}: divergent stage protected_spans"
                if span["start"] < previous_end:
                    return f"{vector_id}: divergent stage protected_spans"
                previous_end = span["end"]
        if stage == "chunk_boundaries":
            boundaries = vector[stage]
            if (
                boundaries[0]["start"] != 0
                or boundaries[-1]["end"] != len(vector["phonemes"])
                or any(
                    not 0 <= boundary["start"] < boundary["end"] <= len(vector["phonemes"])
                    or boundary["end"] - boundary["start"] > (
                        document["hard_input_symbols"]
                        if len(boundaries) == 1
                        else document["max_input_symbols"]
                    )
                    for boundary in boundaries
                )
                or any(
                    left["end"] != right["start"]
                    for left, right in zip(boundaries, boundaries[1:])
                )
            ):
                return f"{vector_id}: divergent stage chunk_boundaries"
        if stage == "reference_audio":
            audio = vector[stage]
            if audio["sample_count"] != vector.get("audio_samples"):
                return f"{vector_id}: divergent stage reference_audio"
            if audio["sample_rate_hz"] != document["sample_rate"]:
                return f"{vector_id}: divergent stage reference_audio"
    return None


def test_corpus_covers_every_task_3_3_category() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    corpus = document["corpus"]
    coverage = corpus["coverage"]
    vectors = document["vectors"]
    by_id = {vector["id"]: vector for vector in vectors}

    assert len(vectors) == 26
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
        assert (
            vector["ipa_len"] <= document["hard_input_symbols"]
            or len(vector["chunk_boundaries"]) > 1
        )
        assert vector["finite"] is True
        assert vector["cleanup_text"] == vector["text"]
        assert vector["normalized_text"] == vector["cleanup_text"]
        assert vector["phonemes"] == vector["ipa"]
        assert vector["phoneme_count"] == vector["ipa_len"]
        assert vector["protected_spans"] == []
        assert vector["chunk_boundaries"][0]["start"] == 0
        assert vector["chunk_boundaries"][-1]["end"] == vector["ipa_len"]
        assert _first_stage_error(vector, document) is None

        audio, sample_rate = sf.read(vector["wav"], dtype="float32")
        assert sample_rate == document["sample_rate"]
        assert audio.size == vector["audio_samples"]
        reference_audio = vector["reference_audio"]
        assert reference_audio["path"] == f"model-tools/reference/{Path(vector['wav']).name}"
        assert reference_audio["sha256"] == hashlib.sha256(Path(vector["wav"]).read_bytes()).hexdigest()
        assert reference_audio["sample_count"] == vector["audio_samples"]
        assert reference_audio["duration_s"] == vector["duration_s"]
        assert reference_audio["finite"] is True


def test_corpus_matches_its_machine_readable_schema() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    errors = sorted(
        Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(document),
        key=lambda error: list(error.path),
    )
    assert not errors, "; ".join(error.message for error in errors)


def test_each_vector_reports_the_first_missing_or_divergent_stage() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    vector = document["vectors"][0]

    missing = dict(vector)
    del missing["normalized_text"]
    assert _first_stage_error(missing, document) == f"{vector['id']}: missing stage normalized_text"

    divergent = dict(vector)
    divergent["normalized_text"] = "different"
    divergent["phonemes"] = "different"
    assert _first_stage_error(divergent, document) == f"{vector['id']}: divergent stage normalized_text"


@pytest.mark.skipif(not PHONEMIZER_ROOT.is_dir(), reason="pinned desktop source is unavailable")
def test_phonemes_match_the_pinned_desktop_reference_pipeline() -> None:
    sys.path.insert(0, str(PHONEMIZER_ROOT))
    from kokoro_sr.phonemes import phonemize_serbian

    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    for vector in document["vectors"]:
        actual = phonemize_serbian(vector["normalized_text"])
        assert actual == vector["phonemes"], f"{vector['id']}: divergent stage phonemes"


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


def test_input_limit_and_no_sentence_boundary_cases_are_pinned() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    by_id = {vector["id"]: vector for vector in document["vectors"]}

    assert [
        by_id[f"input-limit-{position}"]["ipa_len"]
        for position in ("below", "at", "above")
    ] == [506, 507, 508]
    for position in ("below", "at", "above"):
        vector = by_id[f"input-limit-{position}"]
        assert vector["chunk_boundaries"] == [{"start": 0, "end": vector["ipa_len"]}]
    paragraph = by_id["paragraph-no-sentence-boundary"]
    assert paragraph["ipa_len"] > document["max_input_symbols"]
    assert paragraph["chunk_boundaries"] == [
        {"start": 0, "end": 506},
        {"start": 506, "end": 523},
    ]
    assert not any(mark in paragraph["text"] for mark in ".?!")


def test_corpus_provenance_and_regeneration_contract_is_pinned() -> None:
    document = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    corpus = document["corpus"]
    provenance = corpus["input_provenance"]
    generation = corpus["generation"]
    runtime = generation["reference_runtime"]

    assert corpus["version"] == "reference-20260827-task-3.5"
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
    contract = corpus["vector_contract"]
    assert contract["schema_id"] == "serbian-golden-vector"
    assert contract["schema_version"] == 1
    assert tuple(contract["stage_order"]) == STAGE_ORDER
    assert set(contract["stage_semantics"]) == set(STAGE_ORDER)
