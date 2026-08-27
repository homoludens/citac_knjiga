# Serbian Golden Corpus

`vectors.json` is the machine-readable Serbian reference corpus. It contains
22 self-authored, licensed-safe utterances and the v1 task-3.4 vector contract.
The structural schema is `vectors.schema.json`.

## Vector Contract

Every vector records these stages in order:

1. `cleanup_text` is the text after cleanup. The pinned desktop capture has no
   text cleanup stage, so it is an exact copy of `text`.
2. `normalized_text` is the text passed to `phonemize_serbian`. The pinned
   desktop capture has no text normalization stage, so it is an exact copy of
   `cleanup_text`. No normalization behavior is inferred from the audio.
3. `phonemes` is the exact normalized IPA string returned by the pinned
   `kokoro_sr.phonemes.phonemize_serbian`; `phoneme_count` counts Unicode code
   points. `ipa` and `ipa_len` remain compatibility aliases.
4. `token_ids` contains one vocabulary ID per IPA code point with boundary ID
   `0` prepended and appended. `token_count` remains the existing alias.
5. `protected_spans` contains sorted half-open Unicode code-point ranges into
   `normalized_text`. The pinned desktop capture has no protected-span stage,
   so all current values are `[]`.
6. `chunk_boundaries` contains sorted half-open Unicode code-point ranges into
   `phonemes`. Current vectors are all one unsplit range because they are below
   the operational limit; task 3.5 owns limit-edge and oversized cases.
7. `reference_audio` records the relative WAV path, SHA-256, PCM-16 format,
   sample rate, channels, sample count, duration, peak, RMS, and finite-value
   status. Existing flat audio fields remain compatibility aliases.

## Coverage

The `corpus.coverage` object is the authoritative category index. It covers
Latin/Cyrillic equivalence, Serbian diacritics, `lj`/`nj`/`dž`, mixed scripts,
foreign names, abbreviations, numbers, dates, currencies, measurements, Roman
numerals, punctuation, URLs, email addresses, citations, and page artifacts.
The `corpus.equivalence_groups` object identifies pairs whose pinned reference
IPA and token IDs must match exactly.

All input text is self-authored for this project and is not copied from a
third-party publication or dataset.

## Reproduce

From the repository root, run:

```sh
model-tools/.venv/bin/python model-tools/scripts/capture_reference_vectors.py
```

The generator records the pinned Kokoro and `kokoro_sr` revisions, eSpeak-NG
version and command, model and voice checksums, seed `20260826`, one PyTorch
thread, speed `1.0`, and a SHA-256 of the canonical input manifest. The
phoneme, token, and audio fields are deterministic under that contract.
`infer_seconds` is retained for the existing reference-vector contract but is
explicitly excluded from deterministic expectations.

Validate the corpus without loading the model:

```sh
model-tools/.venv/bin/pytest model-tools/tests/test_reference_corpus.py
```
