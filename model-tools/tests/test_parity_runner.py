from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from parity.runner import evaluate_vector, summarize_vectors
from parity.thresholds import load_thresholds


def _thresholds() -> dict:
    return load_thresholds()


def _waveform() -> np.ndarray:
    samples = np.arange(4096, dtype=np.float32)
    return (0.2 * np.sin(samples / 17.0)).astype(np.float32)


def test_evaluate_vector_applies_all_v1_measurements() -> None:
    waveform = _waveform()

    result = evaluate_vector(
        "synthetic",
        waveform,
        waveform.copy(),
        _thresholds(),
        sample_rate_hz=24_000,
        expected_sample_count=waveform.size,
        reference_pred_dur=np.array([1, 2]),
        candidate_pred_dur=np.array([1, 2]),
    )

    assert result["ok"] is True
    assert set(result["metrics"]) == {
        "sample_count",
        "waveform_error",
        "spectral_similarity",
        "silence",
        "clipping",
        "invalid_values",
    }
    assert result["pred_dur_match"] is True
    assert result["metrics"]["spectral_similarity"]["measurements"][
        "stft_magnitude_cosine"
    ]["value"] == 1.0


def test_sample_count_and_waveform_failures_name_the_vector_and_measurement() -> None:
    reference = _waveform()
    candidate = reference[:-1]

    result = evaluate_vector(
        "short-candidate",
        reference,
        candidate,
        _thresholds(),
        sample_rate_hz=24_000,
    )

    assert result["ok"] is False
    assert any("short-candidate: sample_count.absolute_difference_samples" in item for item in result["failures"])
    assert any("short-candidate: waveform_error.mean_absolute_error" in item for item in result["failures"])


def test_summary_keeps_any_vector_failure_even_when_worst_case_is_within_threshold() -> None:
    thresholds = _thresholds()
    passing = evaluate_vector(
        "passing",
        _waveform(),
        _waveform(),
        thresholds,
        sample_rate_hz=24_000,
    )
    weakened = deepcopy(_waveform())
    weakened[0] = 1.0
    failing = evaluate_vector(
        "clipped",
        _waveform(),
        weakened,
        thresholds,
        sample_rate_hz=24_000,
    )

    summary = summarize_vectors([passing, failing], thresholds)

    assert failing["ok"] is False
    assert summary["clipping"]["pass"] is False
    assert summary["clipping"]["measurements"]["clipped_sample_count"]["pass"] is False
