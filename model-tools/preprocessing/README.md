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
exact reference command, upstream tag commit candidate, installed desktop
binary/data fingerprints, and the pinned `kokoro_sr` source hashes. The
installed data hashes are investigation fingerprints only, not a complete
portable data closure.

The selected Android implementation remains `arm64-v8a` native eSpeak-NG
behind a narrow JNI text-to-IPA boundary. No native library or eSpeak data is
checked in here: the complete data closure/native build provenance is not yet
recorded, and eSpeak-NG's GPL-3.0-or-later license conflicts with the current
Android/F-Droid linked-dependency policy. The contract therefore remains
`not_yet_qualified` and must not be treated as Android compatibility evidence.

Later compatibility validation must compare all 26 golden vectors at every
declared preprocessing stage, using exact equality, before enabling model
inference. This task's validator checks only schema, resource bytes, resource
semantics, and contract identity; it does not implement task 3.8's first-
divergence or package-blocking behavior.
