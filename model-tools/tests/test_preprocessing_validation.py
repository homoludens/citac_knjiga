from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.validate_preprocessing import (  # noqa: E402
    CORPUS_PATH,
    GoldenPreprocessingError,
    STAGE_ORDER,
    load_json,
    validate_golden_preprocessing,
)


def test_clean_corpus_passes_all_preprocessing_stages() -> None:
    validate_golden_preprocessing()


@pytest.mark.parametrize("stage", STAGE_ORDER)
def test_each_preprocessing_stage_is_reported_as_first_mismatch(stage: str) -> None:
    document = deepcopy(load_json(CORPUS_PATH))
    vector = document["vectors"][0]
    if stage == "cleanup_text":
        vector[stage] = "changed cleanup"
    elif stage == "normalized_text":
        vector[stage] = "changed normalization"
    elif stage == "phonemes":
        vector[stage] = "changed phonemes"
    elif stage == "token_ids":
        vector[stage] = [0, 0]
    elif stage == "protected_spans":
        vector[stage] = [{"start": 0, "end": 1}]
    else:
        vector[stage] = [{"start": 0, "end": 1}]

    with pytest.raises(
        GoldenPreprocessingError,
        match=rf"vector 'greeting-latin'.*stage '{stage}'",
    ):
        validate_golden_preprocessing(document=document)


def test_mismatch_message_identifies_vector_and_stage() -> None:
    document = deepcopy(load_json(CORPUS_PATH))
    document["vectors"][0]["normalized_text"] = "changed normalized text"

    with pytest.raises(GoldenPreprocessingError) as error:
        validate_golden_preprocessing(document=document)

    assert str(error.value) == (
        "golden preprocessing mismatch: vector 'greeting-latin', "
        "first divergent stage 'normalized_text': expected 'changed normalized text', "
        "got 'Dobar dan, ovo je glas Dragane. Ona čita knjige svakog jutra.'"
    )
