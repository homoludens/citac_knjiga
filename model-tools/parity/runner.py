"""FP32 PyTorch-to-ONNX parity measurements and threshold evaluation."""
from __future__ import annotations

import math
from typing import Any

import numpy as np


def _finite_count(array: np.ndarray) -> int | None:
    try:
        return int(np.count_nonzero(~np.isfinite(array)))
    except TypeError:
        return None


def _output_info(
    waveform: Any,
    sample_rate_hz: int,
    channels: int,
    expected_dtype: str,
) -> dict[str, Any]:
    array = np.asarray(waveform)
    non_finite = _finite_count(array)
    dtype_ok = array.dtype == np.dtype(expected_dtype)
    shape_ok = array.ndim == 1
    contract_ok = bool(
        dtype_ok
        and shape_ok
        and array.size > 0
        and sample_rate_hz == 24_000
        and channels == 1
    )
    return {
        "dtype": str(array.dtype),
        "shape": list(array.shape),
        "sample_count": int(array.size),
        "sample_rate_hz": sample_rate_hz,
        "channels": channels,
        "non_finite_sample_count": non_finite,
        "invalid_output_count": 0 if contract_ok else 1,
        "contract_ok": contract_ok,
    }


def _as_waveform(value: Any) -> np.ndarray:
    array = np.asarray(value)
    if array.ndim != 1:
        return array
    return array


def _periodic_hann(size: int) -> np.ndarray:
    # This is the periodic Hann used by torch.hann_window and standard STFTs.
    index = np.arange(size, dtype=np.float64)
    return 0.5 - 0.5 * np.cos(2.0 * math.pi * index / size)


def _stft_magnitude_cosine(reference: np.ndarray, candidate: np.ndarray) -> float | None:
    n_fft = 1024
    hop_length = 256
    if reference.size != candidate.size or reference.size < n_fft:
        return None

    frame_count = 1 + (reference.size - n_fft) // hop_length
    reference_frames = np.lib.stride_tricks.sliding_window_view(reference, n_fft)[
        ::hop_length
    ][:frame_count]
    candidate_frames = np.lib.stride_tricks.sliding_window_view(candidate, n_fft)[
        ::hop_length
    ][:frame_count]
    window = _periodic_hann(n_fft)
    reference_magnitude = np.abs(np.fft.rfft(reference_frames * window, axis=1)).ravel()
    candidate_magnitude = np.abs(np.fft.rfft(candidate_frames * window, axis=1)).ravel()
    denominator = np.linalg.norm(reference_magnitude) * np.linalg.norm(candidate_magnitude)
    if denominator == 0.0:
        return 0.0
    similarity = np.dot(reference_magnitude, candidate_magnitude) / denominator
    return float(np.clip(similarity, 0.0, 1.0))


def _compare(value: float | int | None, comparator: str, threshold: float) -> bool:
    if value is None:
        return False
    if isinstance(value, bool) or not math.isfinite(float(value)):
        return False
    if comparator == "==":
        return value == threshold
    if comparator == "<":
        return value < threshold
    if comparator == "<=":
        return value <= threshold
    if comparator == ">":
        return value > threshold
    if comparator == ">=":
        return value >= threshold
    raise ValueError(f"unsupported comparator {comparator!r}")


def _metric_values(
    reference: Any,
    candidate: Any,
    threshold_declaration: dict[str, Any],
    sample_rate_hz: int,
    channels: int,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, float | int | None]]:
    audio_contract = threshold_declaration["comparison"]["audio_contract"]
    expected_dtype = audio_contract["dtype"]
    reference_array = _as_waveform(reference)
    candidate_array = _as_waveform(candidate)
    reference_info = _output_info(
        reference_array, sample_rate_hz, channels, expected_dtype
    )
    candidate_info = _output_info(
        candidate_array, sample_rate_hz, channels, expected_dtype
    )

    same_shape = (
        reference_info["contract_ok"]
        and candidate_info["contract_ok"]
        and reference_array.size == candidate_array.size
        and reference_array.shape == candidate_array.shape
    )
    if same_shape:
        difference = np.abs(
            reference_array.astype(np.float64) - candidate_array.astype(np.float64)
        )
        waveform_mean = float(np.mean(difference))
        waveform_max = float(np.max(difference))
    else:
        waveform_mean = None
        waveform_max = None

    candidate_valid = bool(candidate_info["contract_ok"] and candidate_info["non_finite_sample_count"] == 0)
    if candidate_valid:
        candidate_float = candidate_array.astype(np.float64)
        rms = float(np.sqrt(np.mean(np.square(candidate_float))))
        silence_level = threshold_declaration["metrics"]["silence"]["measurements"][
            "silent_sample_fraction"
        ].get("silence_level", 0.0001)
        silent_fraction = float(np.mean(np.abs(candidate_float) <= silence_level))
        clipped_count = int(np.count_nonzero(np.abs(candidate_float) >= 1.0))
        absolute_peak = float(np.max(np.abs(candidate_float)))
    else:
        rms = None
        silent_fraction = None
        clipped_count = None
        absolute_peak = None

    values: dict[str, float | int | None] = {
        "absolute_difference_samples": abs(
            candidate_info["sample_count"] - reference_info["sample_count"]
        ),
        "mean_absolute_error": waveform_mean,
        "maximum_absolute_error": waveform_max,
        "stft_magnitude_cosine": (
            _stft_magnitude_cosine(reference_array, candidate_array)
            if same_shape
            else None
        ),
        "rms_amplitude": rms,
        "silent_sample_fraction": silent_fraction,
        "clipped_sample_count": clipped_count,
        "absolute_peak": absolute_peak,
        "non_finite_sample_count": candidate_info["non_finite_sample_count"],
        "invalid_output_count": candidate_info["invalid_output_count"],
    }
    return reference_info, candidate_info, values


def evaluate_vector(
    vector_id: str,
    reference: Any,
    candidate: Any,
    threshold_declaration: dict[str, Any],
    *,
    sample_rate_hz: int,
    channels: int = 1,
    expected_sample_count: int | None = None,
    reference_pred_dur: Any = None,
    candidate_pred_dur: Any = None,
) -> dict[str, Any]:
    """Evaluate one vector against every declared threshold measurement."""
    reference_info, candidate_info, values = _metric_values(
        reference, candidate, threshold_declaration, sample_rate_hz, channels
    )
    metric_results: dict[str, Any] = {}
    failures: list[str] = []

    for metric_name, metric in threshold_declaration["metrics"].items():
        measurement_results: dict[str, Any] = {}
        for measurement_name, declaration in metric["measurements"].items():
            value = values[measurement_name]
            passed = _compare(value, declaration["comparator"], declaration["threshold"])
            measurement_results[measurement_name] = {
                "value": value,
                "unit": declaration["unit"],
                "comparator": declaration["comparator"],
                "threshold": declaration["threshold"],
                "pass": passed,
            }
            if not passed:
                failures.append(
                    f"{vector_id}: {metric_name}.{measurement_name} "
                    f"value={value!r} {declaration['comparator']} "
                    f"threshold={declaration['threshold']!r}"
                )
        metric_results[metric_name] = {
            "pass": all(item["pass"] for item in measurement_results.values()),
            "measurements": measurement_results,
        }

    result: dict[str, Any] = {
        "vector": vector_id,
        "reference": reference_info,
        "candidate": candidate_info,
        "metrics": metric_results,
        "failures": failures,
        "ok": not failures,
    }
    if expected_sample_count is not None:
        result["expected_sample_count"] = expected_sample_count
        result["reference_sample_count_matches_vector"] = (
            reference_info["sample_count"] == expected_sample_count
        )
    if reference_pred_dur is not None and candidate_pred_dur is not None:
        reference_durations = np.asarray(reference_pred_dur)
        candidate_durations = np.asarray(candidate_pred_dur)
        result["pred_dur_match"] = bool(
            reference_durations.shape == candidate_durations.shape
            and np.array_equal(reference_durations, candidate_durations)
        )
        result["pred_dur_shape_reference"] = list(reference_durations.shape)
        result["pred_dur_shape_candidate"] = list(candidate_durations.shape)
    return result


def summarize_vectors(
    vector_results: list[dict[str, Any]], threshold_declaration: dict[str, Any]
) -> dict[str, Any]:
    """Report declared across-vector aggregations without waiving vector failures."""
    summary: dict[str, Any] = {}
    for metric_name, metric in threshold_declaration["metrics"].items():
        measurement_summary: dict[str, Any] = {}
        for measurement_name, declaration in metric["measurements"].items():
            values = [
                result["metrics"][metric_name]["measurements"][measurement_name]["value"]
                for result in vector_results
                if "metrics" in result
                and metric_name in result["metrics"]
                and measurement_name in result["metrics"][metric_name]["measurements"]
            ]
            aggregation = declaration["aggregation"]["across_vectors"]
            if not values or any(value is None for value in values):
                aggregate = None
            elif aggregation == "maximum":
                aggregate = max(values)
            elif aggregation == "minimum":
                aggregate = min(values)
            else:
                raise ValueError(
                    f"unsupported across_vectors aggregation {aggregation!r}"
                )
            all_vector_pass = all(
                result.get("metrics", {})
                .get(metric_name, {})
                .get("measurements", {})
                .get(measurement_name, {})
                .get("pass", False)
                for result in vector_results
            )
            measurement_summary[measurement_name] = {
                "value": aggregate,
                "aggregation": aggregation,
                "unit": declaration["unit"],
                "comparator": declaration["comparator"],
                "threshold": declaration["threshold"],
                "pass": bool(all_vector_pass and _compare(
                    aggregate, declaration["comparator"], declaration["threshold"]
                )),
            }
        summary[metric_name] = {
            "pass": all(item["pass"] for item in measurement_summary.values()),
            "measurements": measurement_summary,
        }
    return summary
