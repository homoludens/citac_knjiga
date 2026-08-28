# Serbian Phonemization Decision (task 3.6)

Decision date: 2026-08-27

## Decision

Exact Android Serbian phonemization is **not a pure-Kotlin rules/resources
implementation** for the pinned reference. The reference requires the
eSpeak-NG engine and its Serbian data. The selected engineering direction is a
pinned arm64 native eSpeak-NG component and data set behind a narrow JNI
phonemizer boundary. Kotlin may own the reference-equivalent post-processing,
vocabulary audit, boundary tokens, and chunking, but it must not replace the
eSpeak-NG text-to-IPA step with an approximation.

This is a task 3.6 dependency decision only. No native build, JNI bridge,
portable resource set, or Android code is introduced here.

## Source Evidence

The source was inspected at `homoludens/kokoro-serbian` commit
`ca5590d9576f63b0763e51a73de0596d47f05425`:

- `src/kokoro_sr/phonemes.py` SHA-256
  `be84544903e0657d8579e567f3dae1170a2a57f3ca221911ebc49bcb9525c267`.
  `phonemize_serbian()` runs the external command
  `espeak-ng -q --ipa=3 -v sr --stdin`, captures stdout, then calls
  `normalize_ipa()` and `audit_ipa()` (`phonemes.py:46-58`).
- `normalize_ipa()` only performs Unicode NFC, invisible-character, tie-bar,
  syllabic-mark, affricate, whitespace, and trimming transformations
  (`phonemes.py:24-32`). It does not generate pronunciations.
- The 115-symbol `KOKORO_SYMBOLS` set and the audit reject output outside the
  pinned Kokoro vocabulary (`phonemes.py:10-18`, `35-43`). This is a later
  validation step, not a replacement for phonemization.
- `src/kokoro_sr/speak_2.py:23-29` calls that same function before the model
  receives IPA. There is no alternate Kotlin, table, or embedded phonemizer in
  the pinned `kokoro_sr` source.
- The checked-in runtime record identifies `/usr/bin/espeak-ng` version
  `1.52.0`, `/usr/share/espeak-ng-data`, and the Serbian dictionary
  `sr_dict` (`model-tools/runtime-pins.md:62-70`). The source repository's
  `pyproject.toml` does not declare eSpeak-NG as a Python package dependency;
  it is an operating-system runtime dependency.
- The local reference run passed all 8 focused corpus tests, including the
  pinned-source phoneme comparison for all 26 golden vectors. The vector
  provenance records the same command, version, `kokoro_sr` revision, and
  Kokoro revision (`model-tools/reference/vectors.json` and
  `model-tools/tests/test_reference_corpus.py:237-245`, `293-317`).

The eSpeak-NG 1.52.0 upstream release also documents that eSpeak-NG is written
in C, exposes a shared-library API, supports Android, and can translate text
to phoneme codes. Its dictionary documentation describes language rule files
and lookup dictionaries compiled into language data files. This confirms that
the dependency is an engine plus data, not a small Serbian character mapping:

- <https://github.com/espeak-ng/espeak-ng/tree/1.52.0>
- <https://github.com/espeak-ng/espeak-ng/blob/1.52.0/docs/integration.md>
- <https://github.com/espeak-ng/espeak-ng/blob/1.52.0/docs/dictionary.md>

The installed reference binary and key data files were also fingerprinted for
investigation, not as an Android artifact pin:

| Reference item | SHA-256 |
|---|---|
| `/usr/bin/espeak-ng` | `76122c78e89cf20c0eabec6c8b79838ba5d91ade7399748b2d78073940bd40b8` |
| `espeak-ng-data/sr_dict` | `770cae9c516e48af7896629faf4abefaf041e1fd4ae184011aad07b97196f613` |
| `espeak-ng-data/phondata` | `a0b643b155cb6b12628d9e7865b57d9fca0d35844614f2594a5e009c80c80bb4` |
| `espeak-ng-data/phonindex` | `384e5fa6f714ba5356c58008249b78699c31c1ad044b243068d263fb806b7d73` |
| `espeak-ng-data/phontab` | `1b40690667e1e9aa1ba5e5234773c799e7e72ea751426e5150423d53c3f24fa2` |

## Implementation Direction

The Android candidate must:

- build or otherwise obtain eSpeak-NG 1.52.0 for `arm64-v8a` as native source,
  not use the host executable or Python;
- expose only a narrow text-to-IPA operation to Kotlin, with the Serbian
  voice, `--ipa=3` equivalent, and error behavior fixed by the provenance
  contract;
- package the complete data closure required by that build, including the
  Serbian dictionary and shared phoneme/character/intonation data, with a
  checksum manifest; and
- apply the pinned Kotlin normalization and vocabulary audit after native
  output, then compare every intermediate output against the 26 golden
  vectors before enabling model inference.

The current reference identifies the eSpeak-NG version and data path, but does
not prove that the installed binary was built from a recorded eSpeak-NG source
commit. The upstream `1.52.0` tag resolves to commit
`4870adfa25b1a32b4361592f1be8a40337c58d6c`; this is a provenance candidate for
the Android build, not a claim about the installed `/usr/bin/espeak-ng`.
The native task must record the resolved source commit, local patches, build
flags, NDK/toolchain, ABI, complete data-file hashes, native library hashes,
and a reproducible build command. It must separately verify native output
against the current reference command. The five hashes above are useful
diagnostics but are not a complete data-closure pin.

## Rejected Alternative

Reject a pure-Kotlin phonemizer based on Serbian letter rules, copied golden
outputs, or a hand-maintained dictionary. It could reproduce the existing
fixtures, but the pinned behavior includes eSpeak-NG's language rules,
dictionary lookup, number handling, punctuation and context behavior. The
current source provides no evidence that these semantics are fully finite or
captured by the golden corpus. A full Kotlin reimplementation of that engine
and data would be a new phonemizer, not a portable representation of the
pinned implementation, and would require a new parity qualification.

Kotlin-only handling remains appropriate for the already evidenced
`normalize_ipa()` transformations, Kokoro vocabulary lookup, boundary token
insertion, voice-row selection, and model-safe chunking. Those stages do not
justify removing the native dependency.

## Dependency And License Decision

The eSpeak-NG upstream README states that eSpeak-NG is released under GPL
version 3 or later and separately identifies a 2-clause BSD compatibility
implementation for Windows `getopt`. The relevant links are the 1.52.0
`COPYING`, `README.md`, and `COPYING.BSD2` files in the upstream tree.

Consequences for this project:

- Treat the eSpeak-NG engine as `GPL-3.0-or-later`. Treat all packaged data as
  requiring an explicit upstream file-level notice audit; the existing
  inventory shorthand `+data LGPL-2.1+` is not evidence that the Serbian data
  closure has that license.
- A JNI-linked native library is an Android application dependency and must
  not be added to the current Android/F-Droid release under the project rule
  that GPL/AGPL components are not linked into the app. This is a release
  compatibility blocker, not permission to silently ship an unlicensed or
  unpinned binary.
- If the project later changes that policy or obtains legal approval, the
  release must provide the applicable GPL license text, copyright notices,
  corresponding source and build information, modification notices, complete
  data notices, and any applicable installation information. A prebuilt AAR,
  `.so`, or data archive without that provenance is not acceptable.
- eSpeak-NG licensing is independent of the blocked Dragana/Juzne vesti model
  licensing. The model-package legal gate and attribution requirements remain
  unchanged.

Until the native dependency is built and qualified, the desktop path remains the
only supported exact phonemization path. No Android model package may claim
Android compatibility merely because its ONNX tensor boundary passed desktop
parity.

## Resolution (2026-08-27, Marko)

The project owner changed the GPL policy: the app **links the native eSpeak-NG
component and accepts GPL-3.0-or-later** for the whole application. The earlier
"no GPL/AGPL linking into the app" rule is rescinded for this dependency.

Consequences recorded:

- The application license becomes **GPL-3.0-or-later** (linking the GPL
  eSpeak-NG engine makes the combined work GPL). GPL is an open-source license,
  so the spec's "open source" and "F-Droid-compatible" goals are not
  contradicted by the license alone.
- F-Droid compatibility **remains the target** (F-Droid distributes GPL apps)
  but is now conditional on: (a) a file-level license audit of the complete
  eSpeak-NG data closure, (b) source-buildability of the native component (no
  prebuilt `.so`/AAR without recorded source, local patches, and build
  provenance), and (c) the GPL obligations — license text, copyright notices,
  corresponding source, modification notices, and installation information.
- The "release compatibility blocker" framing above is lifted as a *policy*
  blocker. The native source/data provenance is now recorded under
  `model-tools/native/`; remaining open items are Android qualification and
  release reproducibility, tracked under tasks 4.5 and 12.3/12.4, not as a
  license-policy decision.
- The Dragana/Južne vesti model licensing gate is independent and unchanged.

## Unresolved Risks

- The native Android library builds for `arm64-v8a`, but no Android ABI load,
  memory, or device parity evidence exists yet.
- The checked-in data closure and build provenance are recorded, but release
  reproducibility still requires the pinned toolchain and source-build steps to
  be exercised in clean CI under task 12.3/12.4.
- Native output has been compared with a host-equivalent implementation of the
  pinned CLI behavior for all 26 vectors; Android instrumentation must still
  prove the same result.
- GPL obligations for the linked engine and every packaged data file still
  require a file-level release audit and corresponding source/build material.

The original decision did not implement task 3.7 portable resources or Android
integration. Subsequent task 3.7 and 4.5 work records the accepted native
implementation and its current qualification status.
