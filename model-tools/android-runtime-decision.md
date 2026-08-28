# Android Inference Runtime Decision (task 2.8)

## Decision

Select direct ONNX Runtime for the Android inference boundary. Pin the first
Android implementation target to:

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
```

The dependency is the Maven Central release AAR, not a nightly, version range,
custom build, or Sherpa-ONNX package. This is a runtime selection for the next
Android implementation tasks. It is not an Android parity or device
qualification result.

The selected runtime preserves design decision 2: Android receives `input_ids`
(`int64 [1, seq_len]`), one selected Dragana style row (`float32 [1, 256]`),
and scalar `speed` (`float32`), then consumes `waveform` (`float32`, 24 kHz
mono) and `pred_dur` (`int64`). Text preprocessing, eSpeak-NG, vocabulary
lookup, boundary tokens, chunking, and voice-row selection remain outside the
ONNX graph.

## Comparable Evidence

| Concern | Direct ONNX Runtime | Sherpa-ONNX |
|---|---|---|
| Candidate | Graph SHA-256 `f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6`; `ai.onnx` opset 18; 65 standard operator types | Same graph after disposable metadata, voice, and token-file adaptation |
| Desktop execution | ORT `1.29.0`, CPU EP, all 7 vectors | Sherpa `1.13.6`, source commit `34eba5a27220026b5981b633981c53205515067d`, all 7 vectors execute |
| Serbian boundary | Uses the exact committed token IDs and selected style rows | High-level API owns text-to-phoneme conversion and does not accept precomputed token IDs |
| Output evidence | `fp32-parity-v1` passes 7/7: exact sample counts, MAE `0.0034103 <= 0.01`, max error `0.0645357 <= 0.1`, spectral cosine `0.9993543 >= 0.99`, finite/non-clipped audio | Every vector has a different sample count; the frontend logs skipped unknown phoneme `U+0291` |
| Android evidence | A versioned Android AAR is published and its ABI contents were inspected, but it has not run this graph on Android | No Android AAR build, ABI inspection, or device run in the bounded experiment |

The direct path is the only path with committed exact-Serbian tensor-boundary
parity evidence. Sherpa's successful graph execution proves format and
operator feasibility after adaptation, not compatibility with the project's
Serbian frontend. It is rejected because its frontend does not implement the
`kokoro_sr` normalization and vocabulary contract: it maps text through its
Piper/eSpeak path, skips `U+0291`, and produces different sample counts for all
7 vectors. A Sherpa source patch or token-injection frontend would be a new
runtime integration, not a drop-in replacement, and would need a new parity
gate.

## Pin And Packaging Strategy

- Resolve only from Maven Central using the exact coordinate
  `com.microsoft.onnxruntime:onnxruntime-android:1.29.0`.
- Reject `latest.release`, `+`, ranges, nightly artifacts, and an unpinned
  source build for the MVP target.
- The inspected AAR has SHA-256
  `e97540ca78fe36f6fe2013f82843414fb843b6c7681fb04644cba5e1406662dd` and
  Maven SHA-1 `22c4a984c9c6f86c188b4d5ae792527db4527889`. Record the AAR
  SHA-256 in Gradle dependency verification when the Android project is
  created, and keep dependency locking and checksum verification required for
  release task 12.3.
- Keep the ONNX model and Dragana voice package outside the application APK
  and subject to the existing legal release gate. Selecting an MIT runtime
  does not clear the blocked model-weight rows or authorize their
  redistribution.
- Do not add Sherpa-ONNX or its frontend to the Android dependency set for this
  decision. eSpeak-NG is a separate, accepted native Android dependency from
  task 3.6; its GPL source, notices, data audit, and reproducible-build
  obligations remain tracked independently.

## Provider, ABI, And Thread Contract

- The CPU execution provider is the acceptance baseline and fallback. The
  first Android proof should use sequential execution with ORT intra-op `1`
  and inter-op `1`, matching the committed desktop parity run's bounded
  `1`/`1` configuration. This bounds contention and makes failures easier to
  diagnose; it is not a performance claim.
- The same AAR contains XNNPACK. XNNPACK is an explicitly measured provider
  variant, not an automatic replacement for the CPU baseline. If enabled, set
  its own `intra_op_num_threads` explicitly, keep the ORT pool bounded, and
  retain CPU fallback for operators XNNPACK does not support. Provider
  assignment and output parity must be recorded separately.
- Do not enable NNAPI for the initial path. It remains the conditional
  optimization experiment described by task 5.3, after CPU/XNNPACK results
  exist.
- The inspected AAR contains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
  native directories. The application target is Android 11+ `arm64-v8a`; use
  an explicit ABI filter so other native libraries are not packaged. ABI
  loading and the final APK contents still require Android validation.
- The graph has no custom operator domain, but its 65 operator types, dynamic
  sequence input, and data-dependent waveform output must be exercised by the
  Android AAR before the model is called Android-compatible.

## Remaining Android Validation

Task 2.8 does not claim any of the following are complete:

- Build the Android module against the pinned AAR and verify native loading on
  Android 11+ `arm64-v8a`.
- Create a session with the declared CPU configuration, then test the
  explicitly configured XNNPACK variant and record provider assignment,
  fallback, and errors.
- Run all 7 golden vectors through the Android tensor boundary, including
  dynamic lengths, `int64` IDs, `float32` style/speed inputs, 24 kHz mono
  output, `pred_dur`, finite samples, and the declared `fp32-parity-v1`
  metrics.
- Verify Android preprocessing and voice-row selection separately against the
  golden intermediates. Task 3.6 resolved the exact eSpeak-backed preprocessing
  as an accepted native implementation; Android execution and reproducible
  release obligations are tracked by tasks 4.5 and 12.3/12.4.
- Measure model load, peak memory, real-time factor, CPU, thermal behavior,
  battery use, and stability on the Poco F3. Those are task 5 device gates,
  not evidence supplied by this selection.

## Sources

- Direct graph manifest: `model-tools/export/manifest.json`
- ONNX validation: `model-tools/export/validation.json`
- Desktop parity: `model-tools/parity/fp32-parity-report.json` and
  `model-tools/parity/fp32-parity-v1-decision.md`
- Sherpa experiment: `model-tools/sherpa-onnx-compatibility.md`
- Android artifact: <https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.29.0/onnxruntime-android-1.29.0.aar>
- Android artifact POM/license: <https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.29.0/onnxruntime-android-1.29.0.pom>
- ORT XNNPACK provider guidance: <https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html>
