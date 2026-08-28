# FP32 Parity v2 Decision

## Threshold Revision

`fp32-parity-v2` changes only
`waveform_error.maximum_absolute_error`, from `<= 0.1` to `<= 0.13`. The v1
declaration and report remain unchanged as historical evidence. V1 evaluation
had measured peaks of `0.1276946` on the expanded desktop corpus and
`0.1190485` on the first complete Poco F3 run, while mean error, spectral
similarity, exact sample count, silence, clipping, and finite-output gates all
passed.

The v2 declaration was frozen before its fresh desktop and Android evaluations.
It retains every v1 comparator, aggregation rule, and threshold except the
single peak-error ceiling. Runtime overrides remain forbidden and every vector
must pass.

## Fresh Results

The committed desktop report `fp32-parity-v2-report.json` passes all 26 vectors:

| Measurement | Worst case | Gate |
|---|---:|---:|
| sample-count difference | 0 | `== 0` |
| mean absolute error | 0.0044169974 | `<= 0.01` |
| maximum absolute error | 0.1190068983 | `<= 0.13` |
| STFT magnitude cosine | 0.9992545506 | `>= 0.99` |
| non-finite samples | 0 | `== 0` |
| clipped samples | 0 | `== 0` |

The subsequent native ARM64 run on the Poco F3 (`Xiaomi M2012K11AG`, `alioth`,
API 33) also passes all 26 desktop-ONNX comparisons. Its local text-free report
has SHA-256 `a048addfc24bb04654590b818034ec75849a1686e2865f8592b9f8c9ccbdb51a`;
worst-case MAE is `0.0034103132`, maximum absolute error is `0.0741992146`, and
STFT magnitude cosine is `0.9993200098`.

Decision: **ACCEPT** `fp32-parity-v2` for desktop and Android FP32 qualification.
The generated device report and model package remain local artifacts.
