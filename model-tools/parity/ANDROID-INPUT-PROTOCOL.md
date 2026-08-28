# Android Device Parity Input Protocol v1

`model-tools/scripts/export_onnx_vectors.py` produces an external parity bundle
for task 4.9. The bundle is deliberately separate from the APK and model
package. It contains:

- `manifest.json`: vector IDs, float32 little-endian WAV paths, audio metadata,
  checksums, and generation provenance. It contains no source text or token IDs.
- `inputs.json`: matching vector IDs, ONNX token-ID chunks, speed, and the same
  provenance. It contains no source text.
- `audio/<vector-id>.wav`: raw desktop ONNX waveform as IEEE float32 mono WAV at
  24 kHz.

Generate it from the repository root after the ignored local ONNX export is
available:

```sh
model-tools/.venv/bin/python model-tools/scripts/export_onnx_vectors.py \
  --output-dir /tmp/citac-knjiga-desktop-onnx-vectors
```

The command evaluates all 26 reference IDs, including chunked inputs. The
exported manifest records the ONNX checksum, reference-vector checksum,
`fp32-parity-v1` version, runtime/provider, thread counts, and the fact that
the ONNX graph RNG is unseeded. It does not claim Android parity.

## Android Adapter

`DesktopOnnxParityVectorLoader.load(bundleDirectory)` verifies the two JSON
files, rejects source-text fields and unsafe paths, checks each WAV size and
SHA-256, parses only float32 mono 24 kHz WAV, and validates token boundaries,
vocabulary IDs, chunk lengths, and speed. It returns
`DesktopOnnxParityVector` values. For a chunked vector,
`AndroidDeviceParityRunner.runInstalledAndPersist` invokes the existing
`OnnxTtsSession` once per token chunk, concatenates the outputs, and passes the
single result to the existing `DeviceParityEvaluator`.

Example integration shape:

```kotlin
val vectors = DesktopOnnxParityVectorLoader.load(bundleDirectory)
AndroidDeviceParityRunner().runInstalledAndPersist(
    modelPackageStore, vectors, context, DeviceParityReportStore(reportDirectory),
)
```

The report remains app-private and is written by `DeviceParityReportStore`.
The adapter does not define metrics or alter thresholds. The bundle and report
are local evidence and must not be committed as generated artifacts.

## Native ARM64 Qualification

The production entry point is the opt-in `runsOptInProductionParityAgainstExternalPackage`
test in `DeviceParityAndroidTest`. It is separate from the synthetic fixture test
and is skipped unless the instrumentation argument `production_parity=true` is
explicitly supplied. It requires the test process to run natively as
`arm64-v8a`, loads exactly 26 vectors from `files/parity-input/`, verifies
`files/model-packages/active.zip` through `ModelPackageStore`, invokes
`AndroidDeviceParityRunner.runInstalledAndPersist`, and persists the report at
`files/parity-reports/device-parity-report.json`.

The current library instrumentation target is
`com.homoludens.citacknjiga.tts.onnx.test`. From the repository root, prepare
the phone without adding the generated 325 MB model package or WAV bundle to
the repository:

```sh
APP_ID=com.homoludens.citacknjiga.tts.onnx.test
BUNDLE=/tmp/citac-knjiga-desktop-onnx-vectors
MODEL_PACKAGE=/path/to/verified-model-package.zip
REMOTE=/data/local/tmp/citac-knjiga-parity

ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :tts-onnx:assembleDebugAndroidTest
adb install -r tts-onnx/build/outputs/apk/androidTest/debug/tts-onnx-debug-androidTest.apk

adb shell run-as "$APP_ID" rm -rf files/parity-input files/parity-reports files/model-packages
adb shell run-as "$APP_ID" mkdir -p files/parity-input files/parity-reports files/model-packages
adb shell rm -rf "$REMOTE"
adb shell mkdir -p "$REMOTE/audio"
adb push "$BUNDLE/manifest.json" "$REMOTE/"
adb push "$BUNDLE/inputs.json" "$REMOTE/"
adb push "$BUNDLE/audio/." "$REMOTE/audio/"
adb push "$MODEL_PACKAGE" "$REMOTE/model-package.zip"
adb shell chmod -R 755 "$REMOTE"
adb shell run-as "$APP_ID" cp "$REMOTE/manifest.json" files/parity-input/manifest.json
adb shell run-as "$APP_ID" cp "$REMOTE/inputs.json" files/parity-input/inputs.json
adb shell run-as "$APP_ID" cp -R "$REMOTE/audio" files/parity-input/
adb shell run-as "$APP_ID" cp "$REMOTE/model-package.zip" files/model-packages/active.zip
adb shell rm -rf "$REMOTE"

adb shell am instrument -w \
  -e production_parity true \
  -e class com.homoludens.citacknjiga.tts.onnx.DeviceParityAndroidTest#runsOptInProductionParityAgainstExternalPackage \
  "$APP_ID/androidx.test.runner.AndroidJUnitRunner"

adb exec-out run-as "$APP_ID" cat files/parity-reports/device-parity-report.json \
  > /tmp/citac-knjiga-device-parity-report.json
```

The test fails, and still persists a non-passing report, if the package is not
verified, the bundle is incomplete, the device is not native ARM64, inference
fails, or any frozen metric threshold fails. Inspect the pulled JSON before
accepting evidence. Clean all private evidence and any staging file after
review:

```sh
adb shell run-as "$APP_ID" rm -rf files/parity-input files/parity-reports files/model-packages
adb shell rm -rf "$REMOTE"
rm -f /tmp/citac-knjiga-device-parity-report.json
```
