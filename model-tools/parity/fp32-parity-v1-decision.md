# FP32 Parity v1 Decision

## Scope

This is the task 2.6 decision record for the FP32 ONNX candidate identified by
SHA-256 `f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6`.
The candidate was evaluated against the frozen
`model-tools/parity/fp32-thresholds-v1.json` declaration. No threshold,
comparator, aggregation rule, or runtime override was changed.

## Formal Gate Result

Decision: **ACCEPT** the candidate for the direct desktop FP32 parity gate.

The runner evaluated all 7 reference vectors and every declared measurement.
The following are the worst-case values from a fresh single-threaded CPU run
on 2026-08-27:

| measurement | observed | comparator | threshold | result |
|---|---:|:---:|---:|:---:|
| `sample_count.absolute_difference_samples` | 0 | `==` | 0 | PASS |
| `waveform_error.mean_absolute_error` | 0.003529874611964622 | `<=` | 0.01 | PASS |
| `waveform_error.maximum_absolute_error` | 0.0715655516833067 | `<=` | 0.1 | PASS |
| `spectral_similarity.stft_magnitude_cosine` | 0.9993412436210944 | `>=` | 0.99 | PASS |
| `silence.rms_amplitude` | 0.08024822363740361 | `>` | 0.001 | PASS |
| `silence.silent_sample_fraction` | 0.11720512820512821 | `<=` | 0.995 | PASS |
| `clipping.clipped_sample_count` | 0 | `==` | 0 | PASS |
| `clipping.absolute_peak` | 0.49204105138778687 | `<` | 1.0 | PASS |
| `invalid_values.non_finite_sample_count` | 0 | `==` | 0 | PASS |
| `invalid_values.invalid_output_count` | 0 | `==` | 0 | PASS |

There are no formal threshold failures to resolve or accept as exceptions.
The runner is fail-closed: it evaluates each measurement for each vector, and
its across-vector summary cannot turn a failing vector into a pass.

## Route-Deviation Disposition

The exact TorchSTFT comparison is intentionally not the formal baseline. The
formal runner compares ONNX Runtime using `CustomSTFT` with the PyTorch
`KModelForONNX` export path also using `CustomSTFT` (`disable_complex=True`).
This isolates ONNX conversion fidelity. The committed manifest separately
records the exact TorchSTFT diagnostic drift, whose cosine is about 0.798-0.804
and whose maximum absolute error is about 0.154-0.198.

That diagnostic drift is the documented, accepted route deviation caused by
the real-arithmetic CustomSTFT approximation (including its skipped
DC/Nyquist doubling) and the unseedable ONNX vocoder RNG. It is not a failed
`fp32-parity-v1` measurement, is not hidden by aggregation, and does not claim
exact TorchSTFT equivalence. The candidate is accepted for this conversion
gate; any decision to replace this route remains outside task 2.6 and belongs
to the explicitly bounded task 2.7/2.8 work.

## Evidence Commands

```text
model-tools/.venv/bin/python model-tools/scripts/validate_parity_thresholds.py
model-tools/.venv/bin/pytest -q model-tools/tests/test_parity_thresholds.py model-tools/tests/test_parity_runner.py
model-tools/.venv/bin/python model-tools/scripts/run_parity.py --json-output /tmp/citac-knjiga-fp32-parity-v1.json --text-output /tmp/citac-knjiga-fp32-parity-v1.txt
model-tools/.venv/bin/python model-tools/scripts/validate_onnx.py --output /tmp/citac-knjiga-onnx-validation.json
```

The ONNX validation passed independently; its desktop RSS observation is not
a task-2.6 threshold and remains reserved for device qualification work.
No Sherpa-ONNX experiment was run.
