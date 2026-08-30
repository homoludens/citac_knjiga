# Native eSpeak-NG notice

The Android phonemizer builds eSpeak-NG 1.52.0 from source at commit
`4870adfa25b1a32b4361592f1be8a40337c58d6c` and links it into the application.
eSpeak-NG is licensed under `GPL-3.0-or-later`; see the upstream `COPYING` at
<https://github.com/espeak-ng/espeak-ng/blob/1.52.0/COPYING>.

The packaged data closure is generated from that source revision and is listed
in `espeak-data-manifest-v1.json`. The native build has no local source patch.
The app must distribute the corresponding source/build information with any
release containing this native library.

## Reproducible Android build

The recorded local build used NDK `26.1.10909125`, CMake `3.22.1`, Android API
30, `arm64-v8a`, and `Release`:

```sh
cmake -S tts-onnx/src/main/cpp -B /tmp/cita-espeak-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release \
  -DESPEAK_NG_SOURCE_DIR=/path/to/espeak-ng
cmake --build /tmp/cita-espeak-android --parallel
```

The historical observed `libcita_espeak.so` SHA-256 is recorded in the data
manifest. A task-12.4 direct CMake build observed
`bb9a8f2b722de5d4dae35f5ab0d40e25007c155516da82c8f032dbd586553092`; the
unstripped checksum is an output observation, not a checked-in input, because
debug paths and linker metadata can change it. The complete source closure,
runtime dependency exception, generated-output inventory, and validation
commands are in `source-closure-v1.json` and `DEPLOYMENT.md`.
