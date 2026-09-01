# PDF qualification consumer

This directory contains a disposable host gate and an isolated Android
qualification consumer. The Android consumer is deliberately not included in
the root `settings.gradle.kts` and its PdfBox dependency is not in
`document-pdf`. The platform `PdfRenderer` candidate remains rejected because
it renders pixels and does not provide text spans or block geometry.

Run from the repository root:

```sh
python3 pdf-qualification/qualify.py
python3 scripts/check_pdf_qualification.py
```

After Maven artifacts are cached, run the real candidate on both available
targets and assemble the redacted report:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
python3 pdf-qualification/qualify_android.py \
  --serial 2555a240 --serial emulator-5554 --apk-delta 12178
```

The command uses the SDK `adb` and records only targets that actually execute.
The current evidence has passing instrumentation on Poco F3 API 33 arm64 and
the API 35 x86_64 emulator. The overall report remains `no-pass` until the
remaining API matrix and a candidate-free APK baseline are measured.

The checked-in report is binary `no-pass`; no candidate is selected, and
`document-pdf` therefore exposes only the fail-closed unavailable adapter.
Generated fixture files and matrix output are written outside the repository
unless `--output` is explicitly supplied.
