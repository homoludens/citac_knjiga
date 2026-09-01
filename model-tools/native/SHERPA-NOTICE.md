# Sherpa-ONNX Notice

The optional Serbian VITS bridge builds Sherpa-ONNX from source revision
`34eba5a27220026b5981b633981c53205515067d` under Apache-2.0. The source is not
fetched by the Android application and no model or runtime data is downloaded
at runtime. The complete build declaration is in
`sherpa-onnx-source-closure-v1.json`.
The upstream source is `https://github.com/k2-fsa/sherpa-onnx` and the license
text is `https://www.apache.org/licenses/LICENSE-2.0`.

The local JNI bridge is `tts-onnx/src/main/cpp/native_sherpa_vits.cpp`. It uses
Sherpa's VITS model API only; Serbian text normalization and token/vocabulary
validation remain in the application-owned VITS frontend.
