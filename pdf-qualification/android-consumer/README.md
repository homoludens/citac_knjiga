# PdfBox-Android qualification consumer

This is an isolated Android library project. It is not included in the root
`settings.gradle.kts` and the candidate is not a dependency of `document-pdf`.
The pinned candidate is `com.tom-roush:pdfbox-android:2.0.27.0`; its direct
transitive crypto dependencies are Bouncy Castle `1.72`.

Use the repository wrapper with the consumer as the project directory:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
../../gradlew --project-dir pdf-qualification/android-consumer \
  :dependencies --configuration debugRuntimeClasspath

ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
../../gradlew --project-dir pdf-qualification/android-consumer \
  assembleDebug connectedDebugAndroidTest
```

Run `assembleDebug` once with network access to cache Maven artifacts, then
repeat both commands with `--offline`. The instrumentation suite initializes
`PDFBoxResourceLoader`, tests local generated corpus fixtures, collects
`TextPosition` geometry, and writes only redacted evidence below the test app's
private cache directory. It does not open a URI, `ContentResolver`, URL, or
external file path from PDF content.

Measure a candidate APK against the controlled candidate-free baseline after
the artifacts are cached:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
../../gradlew --offline --project-dir pdf-qualification/android-consumer \
  assembleDebugAndroidTest
stat -c '%s' pdf-qualification/android-consumer/build/outputs/apk/androidTest/debug/pdfbox-android-qualification-debug-androidTest.apk
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
../../gradlew --offline --project-dir pdf-qualification/android-consumer \
  -PpdfboxCandidate=false assembleDebugAndroidTest
stat -c '%s' pdf-qualification/android-consumer/build/outputs/apk/androidTest/debug/pdfbox-android-qualification-debug-androidTest.apk
```

The second APK is a size baseline only and is not runnable because it omits the
candidate runtime.

The report is qualification evidence, not production selection evidence. API
levels must be recorded only when the corresponding Android target actually
runs. The production PDF gate remains disabled until the complete required
qualification matrix and closure checks pass.
