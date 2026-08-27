# Serbian Golden Corpus

`vectors.json` is the machine-readable Serbian reference corpus. It contains
22 self-authored, licensed-safe utterances and preserves the task-1.4 vector
contract: input text, normalized IPA, vocabulary token IDs, selected voice row,
and seeded reference WAV metadata. It deliberately does not add cleanup,
protected-span, or chunk-boundary fields; those belong to task 3.4.

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
