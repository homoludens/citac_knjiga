#!/usr/bin/env python3
"""Validate every committed golden vector through desktop preprocessing."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import sys
from typing import Any, Callable, Mapping

try:
    from .validate_preprocessing_contract import (
        CONTRACT_PATH,
        REPO,
        load_json,
        validate_contract,
    )
except ImportError:  # Direct execution puts this scripts directory on sys.path.
    from validate_preprocessing_contract import (  # type: ignore[no-redef]
        CONTRACT_PATH,
        REPO,
        load_json,
        validate_contract,
    )


CORPUS_PATH = REPO / "model-tools/reference/vectors.json"
DEFAULT_PHONEMIZER_ROOT = Path(
    os.environ.get(
        "KOKORO_SR_ROOT",
        "/home/homoludens/projekti/kokoro_tts_srpski_2/src",
    )
)
STAGE_ORDER = (
    "cleanup_text",
    "normalized_text",
    "phonemes",
    "token_ids",
    "protected_spans",
    "chunk_boundaries",
)


class GoldenPreprocessingError(ValueError):
    """Raised when a golden vector cannot be reproduced exactly."""


@dataclass(frozen=True)
class StageMismatch:
    vector_id: str
    stage: str
    expected: Any
    actual: Any

    def message(self) -> str:
        return (
            "golden preprocessing mismatch: "
            f"vector '{self.vector_id}', first divergent stage '{self.stage}': "
            f"expected {_describe(self.expected)}, got {_describe(self.actual)}"
        )


class _Missing:
    pass


_MISSING = _Missing()


def _describe(value: Any) -> str:
    if value is _MISSING:
        return "<missing>"
    if isinstance(value, str) and len(value) > 120:
        return f"<string length={len(value)} prefix={value[:100]!r}>"
    return repr(value)


def _load_reference_phonemizer(
    phonemizer_root: Path = DEFAULT_PHONEMIZER_ROOT,
) -> Callable[[str], str]:
    if not phonemizer_root.is_dir():
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: vector '<corpus>', "
            "first divergent stage 'phonemes': pinned kokoro_sr source is unavailable: "
            f"{phonemizer_root}"
        )
    sys.path.insert(0, str(phonemizer_root))
    try:
        from kokoro_sr.phonemes import phonemize_serbian
    except (ImportError, OSError) as exc:
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: vector '<corpus>', "
            "first divergent stage 'phonemes': cannot load pinned kokoro_sr reference: "
            f"{exc}"
        ) from exc
    return phonemize_serbian


def _text_stage(value: str, resource: Mapping[str, Any], stage: str) -> str:
    operation = resource["text_stages"][stage]["operation"]
    if operation != "identity":
        raise GoldenPreprocessingError(
            f"golden preprocessing mismatch: unsupported {stage} operation in "
            f"normalization-v1.json: {operation}"
        )
    return value


def _chunk_boundaries(phonemes: str, resource: Mapping[str, Any]) -> list[dict[str, int]]:
    limits = resource["limits"]
    hard_limit = limits["hard_phoneme_symbols"]
    operational_limit = limits["operational_phoneme_symbols"]
    if len(phonemes) <= hard_limit:
        return [{"start": 0, "end": len(phonemes)}]

    fallback = resource["reference_behavior"]["oversized_fallback_example"]
    if not fallback or fallback[0]["start"] != 0:
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: chunking-v1.json has no usable "
            "oversized fallback boundary"
        )
    fallback_width = fallback[0]["end"] - fallback[0]["start"]
    if not 0 < fallback_width <= operational_limit:
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: chunking-v1.json fallback exceeds "
            "the operational phoneme limit"
        )

    boundaries: list[dict[str, int]] = []
    start = 0
    while len(phonemes) - start > hard_limit:
        end = min(start + fallback_width, start + operational_limit)
        boundaries.append({"start": start, "end": end})
        start = end
    boundaries.append({"start": start, "end": len(phonemes)})
    return boundaries


def preprocess_vector(
    vector: Mapping[str, Any],
    *,
    normalization: Mapping[str, Any],
    vocabulary: Mapping[str, Any],
    chunking: Mapping[str, Any],
    phonemizer: Callable[[str], str],
) -> dict[str, Any]:
    """Run the reference-equivalent stages without loading the TTS model."""
    cleanup_text = _text_stage(vector["text"], normalization, "cleanup")
    normalized_text = _text_stage(cleanup_text, normalization, "text_normalization")
    phonemes = phonemizer(normalized_text)
    if not isinstance(phonemes, str):
        raise GoldenPreprocessingError(
            f"{vector.get('id', '<unknown>')}: divergent stage phonemes: "
            "reference phonemizer did not return text"
        )

    boundary_token = vocabulary["tokenization"]["boundary_token_id"]
    entries = vocabulary["entries"]
    token_ids = [boundary_token]
    for character in phonemes:
        if character not in entries:
            raise GoldenPreprocessingError(
                f"{vector.get('id', '<unknown>')}: divergent stage token_ids: "
                f"unknown IPA symbol {character!r} (U+{ord(character):04X})"
            )
        token_ids.append(entries[character])
    token_ids.append(boundary_token)

    protected_policy = chunking["reference_behavior"]["current_protected_spans"]
    if protected_policy != "empty":
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: chunking-v1.json changed the current "
            f"protected-span policy to {protected_policy!r}"
        )

    return {
        "cleanup_text": cleanup_text,
        "normalized_text": normalized_text,
        "phonemes": phonemes,
        "token_ids": token_ids,
        "protected_spans": [],
        "chunk_boundaries": _chunk_boundaries(phonemes, chunking),
    }


def first_divergent_stage(
    expected: Mapping[str, Any],
    actual: Mapping[str, Any],
    stage_order: tuple[str, ...] = STAGE_ORDER,
) -> StageMismatch | None:
    """Return the earliest exact stage mismatch, or ``None`` for a match."""
    vector_id = str(expected.get("id", "<unknown>"))
    for stage in stage_order:
        expected_value = expected.get(stage, _MISSING)
        actual_value = actual.get(stage, _MISSING)
        if expected_value != actual_value:
            return StageMismatch(vector_id, stage, expected_value, actual_value)
    return None


def _corpus_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_resources(contract: Mapping[str, Any]) -> tuple[dict[str, Any], ...]:
    resources = {
        item["resource_id"]: load_json(REPO / item["path"])
        for item in contract["resources"]
    }
    return resources["normalization"], resources["vocabulary"], resources["chunking"]


def validate_golden_preprocessing(
    corpus_path: Path = CORPUS_PATH,
    *,
    document: Mapping[str, Any] | None = None,
    phonemizer: Callable[[str], str] | None = None,
    phonemizer_root: Path = DEFAULT_PHONEMIZER_ROOT,
) -> None:
    """Fail closed unless every corpus vector matches every preprocessing stage."""
    contract = validate_contract(CONTRACT_PATH)
    validation = contract["compatibility"]["validation"]
    if tuple(validation["stage_order"]) != STAGE_ORDER:
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: contract stage order is not the "
            f"supported preprocessing order: {validation['stage_order']!r}"
        )

    if document is None:
        expected_corpus_path = REPO / validation["corpus_path"]
        if (
            corpus_path == expected_corpus_path
            and _corpus_sha256(corpus_path) != validation["corpus_sha256"]
        ):
            raise GoldenPreprocessingError(
                "golden preprocessing mismatch: committed corpus checksum does not "
                f"match the contract: {corpus_path}"
            )
        document = load_json(corpus_path)

    vectors = document.get("vectors")
    if not isinstance(vectors, list):
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: corpus vectors must be an array"
        )
    required_count = validation["required_vector_count"]
    if len(vectors) != required_count:
        raise GoldenPreprocessingError(
            "golden preprocessing mismatch: corpus vector count "
            f"expected {required_count}, got {len(vectors)}"
        )

    normalization, vocabulary, chunking = _load_resources(contract)
    reference_phonemizer = phonemizer or _load_reference_phonemizer(phonemizer_root)
    for vector in vectors:
        vector_id = vector.get("id", "<unknown>")
        try:
            actual = preprocess_vector(
                vector,
                normalization=normalization,
                vocabulary=vocabulary,
                chunking=chunking,
                phonemizer=reference_phonemizer,
            )
        except GoldenPreprocessingError as exc:
            if str(exc).startswith(f"{vector_id}:"):
                raise
            raise GoldenPreprocessingError(f"{vector_id}: {exc}") from exc

        mismatch = first_divergent_stage(vector, actual)
        if mismatch is not None:
            raise GoldenPreprocessingError(mismatch.message())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=CORPUS_PATH)
    parser.add_argument("--phonemizer-root", type=Path, default=DEFAULT_PHONEMIZER_ROOT)
    args = parser.parse_args()
    try:
        validate_golden_preprocessing(
            args.corpus,
            phonemizer_root=args.phonemizer_root,
        )
    except (OSError, ValueError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        return 1
    print(json.dumps({
        "ok": True,
        "corpus": str(args.corpus),
        "vector_count": 26,
        "stages": list(STAGE_ORDER),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
