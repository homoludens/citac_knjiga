"""Load and validate the frozen FP32 parity-threshold declaration."""
from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
DEFAULT_THRESHOLDS_PATH = REPO / "model-tools" / "parity" / "fp32-thresholds-v1.json"
VERSION_PATTERN = re.compile(r"^fp32-parity-v[1-9][0-9]*$")
REQUIRED_METRICS = {
    "sample_count",
    "waveform_error",
    "spectral_similarity",
    "silence",
    "clipping",
    "invalid_values",
}
REQUIRED_MEASUREMENTS = {
    "sample_count": {"absolute_difference_samples"},
    "waveform_error": {"mean_absolute_error", "maximum_absolute_error"},
    "spectral_similarity": {"stft_magnitude_cosine"},
    "silence": {"rms_amplitude", "silent_sample_fraction"},
    "clipping": {"clipped_sample_count", "absolute_peak"},
    "invalid_values": {"non_finite_sample_count", "invalid_output_count"},
}


def _is_finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _require(mapping: dict[str, Any], key: str, context: str) -> Any:
    if key not in mapping:
        raise ValueError(f"{context} is missing required field {key!r}")
    return mapping[key]


def validate_thresholds(document: Any) -> dict[str, Any]:
    """Validate the threshold schema and return the unchanged document.

    This deliberately validates policy as well as numeric values: a caller cannot
    load a declaration that permits an unrecorded runtime override or a missing
    vector from being treated as a passing evaluation.
    """
    if not isinstance(document, dict):
        raise ValueError("threshold declaration must be a JSON object")

    if _require(document, "kind", "threshold declaration") != "fp32-parity-thresholds":
        raise ValueError("threshold declaration has an unsupported kind")
    if _require(document, "schema_version", "threshold declaration") != 1:
        raise ValueError("threshold declaration schema_version must be 1")
    version = _require(document, "thresholds_version", "threshold declaration")
    if not isinstance(version, str) or not VERSION_PATTERN.fullmatch(version):
        raise ValueError("thresholds_version must match fp32-parity-vN")
    if _require(document, "declared_before_candidate_evaluation", "threshold declaration") is not True:
        raise ValueError("thresholds must be declared before candidate evaluation")

    comparison = _require(document, "comparison", "threshold declaration")
    if not isinstance(comparison, dict):
        raise ValueError("comparison must be an object")
    if _require(comparison, "reference_vectors", "comparison") != "model-tools/reference/vectors.json":
        raise ValueError("comparison.reference_vectors must point to the committed vectors")
    if _require(comparison, "sample_rate_hz", "comparison") != 24000:
        raise ValueError("comparison.sample_rate_hz must be 24000")
    audio_contract = _require(comparison, "audio_contract", "comparison")
    if not isinstance(audio_contract, dict):
        raise ValueError("comparison.audio_contract must be an object")
    if _require(audio_contract, "channels", "audio_contract") != 1:
        raise ValueError("audio_contract.channels must be 1")
    if _require(audio_contract, "dtype", "audio_contract") != "float32":
        raise ValueError("audio_contract.dtype must be float32")

    policy = _require(document, "policy", "threshold declaration")
    if not isinstance(policy, dict):
        raise ValueError("policy must be an object")
    for key in ("all_vectors_required", "fail_closed", "runtime_override_allowed", "threshold_changes_require_new_version"):
        expected_value = False if key == "runtime_override_allowed" else True
        if _require(policy, key, "policy") is not expected_value:
            expected = "false" if not expected_value else "true"
            raise ValueError(f"policy.{key} must be {expected}")

    metrics = _require(document, "metrics", "threshold declaration")
    if not isinstance(metrics, dict) or set(metrics) != REQUIRED_METRICS:
        raise ValueError(f"metrics must contain exactly {sorted(REQUIRED_METRICS)}")

    for metric_name, metric in metrics.items():
        if not isinstance(metric, dict):
            raise ValueError(f"metrics.{metric_name} must be an object")
        measurements = _require(metric, "measurements", f"metrics.{metric_name}")
        if not isinstance(measurements, dict) or set(measurements) != REQUIRED_MEASUREMENTS[metric_name]:
            raise ValueError(
                f"metrics.{metric_name}.measurements must contain exactly "
                f"{sorted(REQUIRED_MEASUREMENTS[metric_name])}"
            )
        if not isinstance(_require(metric, "pass_condition", f"metrics.{metric_name}"), str):
            raise ValueError(f"metrics.{metric_name}.pass_condition must be text")
        for measurement_name, measurement in measurements.items():
            if not isinstance(measurement, dict):
                raise ValueError(f"{metric_name}.{measurement_name} must be an object")
            for key in ("unit", "aggregation", "comparator", "threshold"):
                _require(measurement, key, f"{metric_name}.{measurement_name}")
            if not isinstance(measurement["unit"], str) or not measurement["unit"]:
                raise ValueError(f"{metric_name}.{measurement_name}.unit must be text")
            aggregation = measurement["aggregation"]
            if not isinstance(aggregation, dict) or set(aggregation) != {"per_vector", "across_vectors"}:
                raise ValueError(f"{metric_name}.{measurement_name}.aggregation must define both stages")
            if not all(isinstance(value, str) and value for value in aggregation.values()):
                raise ValueError(f"{metric_name}.{measurement_name}.aggregation values must be text")
            if measurement["comparator"] not in {"==", "<", "<=", ">", ">="}:
                raise ValueError(f"{metric_name}.{measurement_name}.comparator is unsupported")
            if not _is_finite_number(measurement["threshold"]):
                raise ValueError(f"{metric_name}.{measurement_name}.threshold must be finite")

    # These invariants make accidental broadening of the v1 contract visible to
    # this validator instead of allowing a syntactically valid but weaker gate.
    sample_count = metrics["sample_count"]["measurements"]["absolute_difference_samples"]
    if sample_count["comparator"] != "==" or sample_count["threshold"] != 0:
        raise ValueError("sample count must require an exact zero difference")
    spectral = metrics["spectral_similarity"]["measurements"]["stft_magnitude_cosine"]
    if spectral["comparator"] != ">=" or not 0 < spectral["threshold"] <= 1:
        raise ValueError("spectral similarity must have a cosine floor in (0, 1]")
    silence = metrics["silence"]["measurements"]["rms_amplitude"]
    if silence["comparator"] != ">" or silence["threshold"] <= 0:
        raise ValueError("silence RMS threshold must be a strict positive floor")
    clipping = metrics["clipping"]["measurements"]["clipped_sample_count"]
    if clipping["comparator"] != "==" or clipping["threshold"] != 0:
        raise ValueError("clipping must allow zero clipped samples only")
    invalid = metrics["invalid_values"]["measurements"]["non_finite_sample_count"]
    if invalid["comparator"] != "==" or invalid["threshold"] != 0:
        raise ValueError("invalid values must allow zero non-finite samples only")
    invalid_output = metrics["invalid_values"]["measurements"]["invalid_output_count"]
    if invalid_output["comparator"] != "==" or invalid_output["threshold"] != 0:
        raise ValueError("invalid output must allow zero invalid outputs only")

    return document


def load_thresholds(path: Path = DEFAULT_THRESHOLDS_PATH) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read threshold declaration {path}: {exc}") from exc
    return validate_thresholds(document)
