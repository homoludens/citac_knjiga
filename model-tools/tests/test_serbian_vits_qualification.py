from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from qualification.qualification import (  # noqa: E402
    FINAL_RATE_HZ,
    MODEL_ID,
    NATIVE_RATE_HZ,
    REVISION,
    chunk_boundaries,
    engine_options,
    generation_identity,
    legal_status,
    preprocess_text,
    resample_22050_to_24000,
    run_gates,
    validate_android_matrix,
    validate_quality_corpus,
    validate_resampler_manifest,
    validate_identity,
    validate_package_entries,
    validate_vits_package_roles,
)


def identity() -> dict:
    return {"candidate": {"model_id": MODEL_ID, "revision": REVISION, "resolved_commit": REVISION, "speaker": {"label": "Dragana", "id": 0}, "native_rate_hz": NATIVE_RATE_HZ, "final_rate_hz": FINAL_RATE_HZ, "channels": 1}, "gate_order": ["identity", "legal", "conversion", "desktop_parity", "android_parity", "serbian_quality", "android_matrix"]}


def test_identity_rejects_revision_and_speaker_changes() -> None:
    validate_identity(identity())
    for key, value in (("revision", "main"), ("speaker", {"label": "Other", "id": 1})):
        malformed = identity()
        malformed["candidate"][key] = value
        with pytest.raises(ValueError):
            validate_identity(malformed)


def test_source_manifest_rejects_missing_or_substituted_files(tmp_path: Path) -> None:
    from qualification.qualification import validate_source_manifest

    source = tmp_path / "source.txt"
    source.write_text("pinned", encoding="utf-8")
    import hashlib

    record = {"model_id": MODEL_ID, "requested_revision": REVISION, "resolved_commit": REVISION, "files": [{"path": "source.txt", "size_bytes": 6, "sha256": hashlib.sha256(b"pinned").hexdigest()}]}
    validate_source_manifest(record, tmp_path)
    malformed = deepcopy(record)
    malformed["resolved_commit"] = "main"
    with pytest.raises(ValueError):
        validate_source_manifest(malformed, tmp_path)


def test_legal_record_is_not_clear_when_evidence_is_incomplete_or_contradictory() -> None:
    record = {key: ["recorded"] for key in ("model_code", "training_data", "voice_permission", "conversion_inputs", "licenses", "attributions", "modification_notice")}
    record["distribution_decision"] = "BLOCKED"
    record["contradictions"] = ["permission unresolved"]
    assert legal_status(record) == "BLOCKED"
    incomplete = deepcopy(record)
    incomplete["voice_permission"] = []
    assert legal_status(incomplete) == "UNRESOLVED"


def test_gate_runner_stops_after_unresolved_gate() -> None:
    gates = [{"id": name, "result": "PASS", "evidence": [name], "reason": None} for name in identity()["gate_order"]]
    gates[1]["result"] = "UNRESOLVED"
    result = run_gates(gates)
    assert result["outcome"] == "REJECTED"
    assert all(gate["result"] != "PASS" for gate in result["gates"][2:])


def test_package_is_closed_world_and_rejects_executable_payload() -> None:
    with pytest.raises(ValueError, match="unsafe package entry"):
        validate_package_entries(["model.onnx", "convert.py"], ["model.onnx", "convert.py"])
    with pytest.raises(ValueError, match="mismatch"):
        validate_package_entries(["model.onnx", "notice.json"], ["model.onnx"])


def test_vits_package_requires_model_and_tokens() -> None:
    validate_vits_package_roles(["onnx", "tokens", "notice"])
    with pytest.raises(ValueError):
        validate_vits_package_roles(["onnx", "notice"])
    with pytest.raises(ValueError):
        validate_vits_package_roles(["onnx", "tokens", "lexicon", "lexicon"])


def test_package_schema_identity_is_separate_from_existing_contract() -> None:
    import json

    schema = json.loads((Path(__file__).resolve().parents[1] / "qualification/serbian-vits-model-package-v1.schema.json").read_text())
    assert schema["properties"]["schema"]["const"] == "serbian-vits-model-package:1"


def test_preprocessing_is_deterministic_and_diagnoses_unsupported_input() -> None:
    first = preprocess_text("  Čao  2, npr.  ")
    second = preprocess_text("Čao 2, npr.")
    assert first == second
    assert "два" in first["text"] and "на пример" in first["text"]
    assert preprocess_text("Знак §")["diagnostics"]


def test_chunking_uses_declared_vits_contract() -> None:
    assert chunk_boundaries(510) == [{"start": 0, "end": 510}]
    assert chunk_boundaries(1017) == [{"start": 0, "end": 507}, {"start": 507, "end": 1017}]


def test_resampler_has_declared_length_and_finite_output() -> None:
    output = resample_22050_to_24000([0.1, -0.1] * 2205)
    assert len(output) == round(4410 * FINAL_RATE_HZ / NATIVE_RATE_HZ)
    assert all(abs(sample) < 1.0 for sample in output)


def test_resampler_corpus_and_conditional_engine_boundaries_are_strict() -> None:
    root = Path(__file__).resolve().parents[1] / "qualification"
    import json

    validate_resampler_manifest(json.loads((root / "resampler-v1.json").read_text()))
    validate_quality_corpus(json.loads((root / "quality-corpus-v1.json").read_text()))
    validate_android_matrix(json.loads((root / "android-matrix-v1.json").read_text()))
    assert engine_options({"outcome": "REJECTED"}) == ["kokoro"]
    assert engine_options({"outcome": "ACCEPTED"}) == ["kokoro"]


def test_generation_identity_requires_all_engine_specific_fields() -> None:
    provenance = {"engine": "vits", "model": MODEL_ID, "revision": REVISION, "speaker_id": 0, "preprocessing": "serbian-vits-preprocessing-v1", "native_rate_hz": 22050, "final_rate_hz": 24000, "inference": {"seed": 0}, "resampler": "serbian-vits-resampler-v1", "audio_processing": "pcm-mono-v1"}
    assert generation_identity(provenance) == generation_identity(dict(provenance))
    with pytest.raises(ValueError):
        generation_identity({"engine": "vits"})
