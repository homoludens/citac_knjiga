from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from parity.thresholds import DEFAULT_THRESHOLDS_PATH, load_thresholds, validate_thresholds


def test_active_thresholds_are_complete_and_fail_closed() -> None:
    declaration = load_thresholds()

    assert declaration["thresholds_version"] == "fp32-parity-v2"
    maximum_error = declaration["metrics"]["waveform_error"]["measurements"]["maximum_absolute_error"]
    assert maximum_error["threshold"] == 0.13
    assert declaration["declared_before_candidate_evaluation"] is True
    assert declaration["policy"]["runtime_override_allowed"] is False
    assert set(declaration["metrics"]) == {
        "sample_count",
        "waveform_error",
        "spectral_similarity",
        "silence",
        "clipping",
        "invalid_values",
    }
    assert DEFAULT_THRESHOLDS_PATH.is_file()


def test_threshold_validator_rejects_a_weakened_exact_count_gate() -> None:
    declaration = load_thresholds()
    weakened = deepcopy(declaration)
    weakened["metrics"]["sample_count"]["measurements"]["absolute_difference_samples"]["threshold"] = 1

    with pytest.raises(ValueError, match="exact zero difference"):
        validate_thresholds(weakened)


def test_threshold_validator_rejects_a_missing_required_measurement() -> None:
    declaration = load_thresholds()
    malformed = deepcopy(declaration)
    del malformed["metrics"]["invalid_values"]["measurements"]["invalid_output_count"]

    with pytest.raises(ValueError, match="invalid_values.measurements"):
        validate_thresholds(malformed)
