# Serbian Preprocessing Resources (task 3.7)

This directory contains the platform-neutral, checked-in resources that can be
consumed by a later Kotlin/native implementation:

- `vocabulary-v1.json` is the exact 178-slot Kokoro vocabulary contract with
  the pinned source allowlist and model-config lookup, Unicode-code-point
  lookup, token `0` boundaries, and fail-closed unknown-symbol handling. The
  source allowlist contains 115 symbols; the checked-in model config contains
  114 mapped entries and leaves legacy `$` unmapped, so `$` is not assigned a
  fabricated token ID.
- `normalization-v1.json` records the pinned IPA normalization operations and
  the currently evidenced no-op cleanup/text-normalization stages.
- `chunking-v1.json` records the 507 operational and 510 hard limits, range
  units, protected-span rules, boundary preference, and the verified fallback
  boundary example.
- `preprocessing-contract-v1.json` binds those resources to the pinned
  `kokoro_sr` source, versioned processing stages, model compatibility, golden
  validation expectations, and eSpeak-NG provenance.

All resource paths, UTF-8 JSON serialization, and SHA-256 values are part of
the contract identity. Validate them without model inference:

```sh
model-tools/.venv/bin/python model-tools/scripts/validate_preprocessing_contract.py
model-tools/.venv/bin/pytest -q model-tools/tests/test_preprocessing_contract.py
```

The contract records eSpeak-NG `1.52.0`, Serbian voice `sr`, IPA mode `3`, the
exact reference command, upstream tag commit, installed desktop fingerprints,
and the pinned `kokoro_sr` source hashes. The Android data closure and native
build observation are recorded in `model-tools/native/` and packaged under
`tts-onnx/src/main/assets/`.

The selected Android implementation remains `arm64-v8a` native eSpeak-NG
behind a narrow JNI text-to-IPA boundary. The bridge reproduces the pinned
desktop CLI's bulk-stdin behavior before applying the Kotlin normalization and
vocabulary stages. The contract remains `not_yet_qualified` for the ARM64
production target until ARM64 Android instrumentation and device execution
verify all 26 vectors. eSpeak-NG is
`GPL-3.0-or-later`; release compliance requires the source, notices, and build
information recorded in `model-tools/native/`.

Desktop compatibility validation compares all 26 golden vectors at every
declared preprocessing stage, using exact equality, and reports the first
divergent vector/stage. Model package creation runs this same gate after legal
validation and before writing an archive. It performs no model inference.

Run it directly with:

```sh
model-tools/.venv/bin/python model-tools/scripts/validate_preprocessing.py
```
