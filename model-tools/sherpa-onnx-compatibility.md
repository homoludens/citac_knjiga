# Sherpa-ONNX Compatibility Experiment (task 2.7)

## Decision

**Reject as a drop-in replacement for the Serbian MVP inference boundary.**

Sherpa-ONNX can execute the custom graph after disposable format adaptation,
but this experiment did not establish the required exact Serbian preprocessing
and token identity. Sherpa's high-level Kokoro API owns text-to-phoneme
conversion, uses its Piper/eSpeak integration, and does not accept precomputed
token IDs. The run emitted an unsupported `U+0291` phoneme that the Sherpa
frontend skips. Its generated sample counts differed from the exact custom
token path on all seven reference texts. Direct ONNX Runtime remains the
evidence-backed baseline. This is not the task 2.8 Android runtime selection.

## Scope And Versions

The time-box covered one bounded desktop probe on 2026-08-27:

- Sherpa source commit: `34eba5a27220026b5981b633981c53205515067d`
- Sherpa Python package: `sherpa-onnx==1.13.6` and
  `sherpa-onnx-core==1.13.6`, installed in a disposable environment
- Host: Linux x86_64, Python 3.11.14, CPU execution provider, one inference
  thread
- eSpeak data: `/usr/share/espeak-ng-data`; the pinned Serbian reference
  process remains `/usr/bin/espeak-ng` 1.52.0 with
  `--ipa=3 -v sr --stdin`
- Custom graph SHA-256:
  `f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6`
- Dragana epoch-005 voice SHA-256:
  `0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a`
- Inputs: all seven committed vectors in `model-tools/reference/vectors.json`

No Android SDK/device execution was available in this spike. The result is
desktop API and graph evidence, not Android ABI, memory, performance, or
packaging qualification.

## Disposable Adapter

The checked-in artifacts are not modified. The probe creates a temporary copy
containing:

1. Sherpa metadata (`model_type=kokoro`, `version=1`, `sample_rate=24000`,
   `style_dim=510,1,256`, `n_speakers=1`, `voice=sr`, and required metadata
   fields). The original graph has no ONNX metadata; Sherpa requires these
   fields before it constructs the model.
2. `voices.bin`, made from the checked-in `sr_dragana.pt` tensor as contiguous
   little-endian float32 bytes. The source tensor is `[510, 1, 256]`; Sherpa's
   loader expects `n_speakers * style_dim[0] * style_dim[2]` raw floats and
   selects the row at `min(token_count - 2, 509)`.
3. `tokens.txt`, generated from the custom 178-slot vocabulary. Sherpa's token
   reader accepts one-character symbols and represents the space symbol with
   its id-only line (`16`).

This proves that the graph interface, token vocabulary, and Dragana style
values can be represented in Sherpa's file formats. It does not make those
formats the final model-package schema.

## Evidence

The probe command was:

```text
PYTHONPATH=/tmp/opencode/sherpa-python311 \
  model-tools/.venv/bin/python model-tools/scripts/probe_sherpa_onnx.py \
  --run-sherpa
```

### Exact custom input path

- The pinned `kokoro_sr.phonemize_serbian` output matched the committed IPA
  and token IDs for **7/7** vectors.
- Direct ONNX Runtime accepted the original graph, the exact
  `greeting-latin` token IDs (`69` tokens including boundaries), and Dragana
  row `67`. It returned `124200` samples at 24 kHz, `69` duration values, all
  finite, with peak `0.48` in the recorded run.
- The graph interface remained the task 2.2 contract: `input_ids` int64
  `[1, seq_len]`, `ref_s` float32 `[1,256]`, scalar float32 `speed`, and
  `waveform`/`pred_dur` outputs.

### Sherpa execution path

Sherpa constructed and ran the adapted custom graph for **7/7** texts. Every
result was 24 kHz, finite, and non-empty:

| Vector | Exact custom token path | Sherpa text path |
|---|---:|---:|
| `greeting-latin` | 124200 samples | 125554 samples |
| `greeting-cyrillic` | 124200 samples | 125677 samples |
| `diacritics-latin` | 114000 samples | 119099 samples |
| `diacritics-cyrillic` | 114600 samples | 120984 samples |
| `mixed-digits` | 117600 samples | 115619 samples |
| `punctuation` | 80400 samples | 120552 samples |
| `cyrillic-punctuation` | 78000 samples | 116929 samples |

The table is a single run snapshot, not a Sherpa parity report. The exported
vocoder has unseeded random operators, so waveform peaks are not used as a
parity metric here. The broad per-vector sample-count differences and the
skipped phoneme remain the relevant result.

The Sherpa process also logged three instances of:

```text
Skip unknown phonemes. Unicode codepoint: \U+0291.
```

This is material evidence of a preprocessing/tokenization mismatch, not a
claim that the custom model weights are unusable. The custom Serbian
normalizer explicitly maps `dʑ` to `ʥ`, while the tested Sherpa frontend does
not provide the same normalization contract.

## API And Source Findings

The tested Sherpa source shows:

- `OfflineTtsKokoroModel` requires model metadata and reads raw voice bytes;
  its `Run` method takes token IDs internally and selects a style row, but the
  public Python `OfflineTts` API exposes `generate(text, sid, speed)` rather
  than a precomputed-token call.
- Version-1 Kokoro models use `PiperPhonemizeLexicon`, whose frontend calls
  Piper's embedded eSpeak phonemizer and maps its output through
  `tokens.txt`. It silently skips unknown phonemes in the Kokoro conversion
  loop.
- The Sherpa frontend therefore is not the pinned
  `kokoro_sr.phonemize_serbian` process. Supplying `voice=sr` selects an
  eSpeak voice; it does not import the project's normalization, vocabulary
  audit, or chunking rules.
- The custom graph has no custom ONNX operator domain and did execute through
  Sherpa's CPU ONNX session after metadata insertion, so graph operator support
  is not the rejection reason.

## Limits And Implication

The probe did not inspect Sherpa's private token vector at the Python API
boundary, did not build its Android AAR, and did not measure an Android device.
The sample-count divergence and explicit skipped-phoneme diagnostics are
enough to reject exact Serbian compatibility for this gate. Making Sherpa
viable would require a maintained custom frontend/token injection path (or a
Sherpa source patch), an Android eSpeak/data integration decision, and a new
parity run against the declared custom preprocessing outputs. Those are
additional work, not silently accepted compatibility.

The direct ONNX Runtime decision remains unchanged: it already consumes the
verified token/style/speed boundary and is the only path with the committed
desktop parity evidence. Android runtime selection and pinning remain task
2.8.

## Reproduction

Run from the repository root. The command requires the existing `model-tools`
environment, the pinned `kokoro_sr` source path, `/usr/share/espeak-ng-data`,
and a disposable Sherpa package on `PYTHONPATH`. The probe exits non-zero when
Sherpa is requested but unavailable or its version is not the tested version.

```text
PYTHONPATH=/tmp/opencode/sherpa-python311 \
  model-tools/.venv/bin/python model-tools/scripts/probe_sherpa_onnx.py \
  --run-sherpa
```
