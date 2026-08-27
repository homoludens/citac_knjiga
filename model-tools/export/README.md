# Export wrapper (task 2.1)

Deterministic tensor-level wrapper around the pinned Dragana Kokoro-82M
bundle. It is the single desktop surface that task 2.2 exports to ONNX and
the Android runtime boundary consumes (design decision 2: token IDs + a
selected style row + speed in, float PCM out). No preprocessing is hidden in
the graph path.

- `export/wrapper.py` — `DraganaExportWrapper` (`torch.nn.Module`)
- `scripts/verify_export_wrapper.py` — verification against `reference/vectors.json`

## Interface

```python
wrapper = DraganaExportWrapper(bundle_dir)   # checksum-verified load, eval(), CPU
waveform, pred_dur = wrapper(input_ids, ref_s, speed, seed=SEED)
```

Inputs:

| name | dtype | shape | contract |
|---|---|---|---|
| `input_ids` | int64 | `[1, L+2]` | `L+2` in `[2, 512]` (hard model limit 510 phonemes; operational cap 507); values `0..177` (vocab 178 slots); first and last token must be the `0` boundary token |
| `ref_s` | float32 | `[1, 256]` | one **already-selected** Dragana style row; all values finite |
| `speed` | float | scalar | `> 0`, finite |
| `seed` | int | scalar | `>= 0`, explicit (keyword); `torch.manual_seed(seed)` is applied by the wrapper immediately before inference |

Outputs:

| name | dtype | shape | notes |
|---|---|---|---|
| `waveform` | float32 | `[N]` | mono 24 kHz, magnitude < 1.0; `N` is data-dependent |
| `pred_dur` | int64 | `[L+2]` | per-token predicted frame counts, `>= 1` |

`DraganaExportWrapper.voice_table` (float32 `[510, 1, 256]`) is the full
Dragana style table. Row selection is the **caller's** responsibility:
`row_index = min(len(ipa), 509)` (re-implemented in Kotlin for Android). The
wrapper never selects rows.

**Shape note:** the pinned tensor path slices `ref_s[:, 128:]` /
`ref_s[:, :128]` (`kokoro/model.py:104,118`), which requires a 2-D row
`[1, 256]`. A 3-D `[1, 1, 256]` row slices to `[1, 0, 256]` and fails inside
`DurationEncoder` (verified 2026-08-26, epoch-005 bundle). The `[1, 1, 256]`
phrasing elsewhere refers to the row *slot* inside the 510-row table, not to
the tensor passed to the model.

Validation errors are `TypeError` (dtype/type) or `ValueError` (shape/range/
boundary tokens/finiteness), with the offending value in the message.

## Determinism contract

- The **caller** sets `torch.set_num_threads(1)` once at process start; the
  wrapper refuses to run otherwise.
- The wrapper calls `torch.manual_seed(seed)` immediately before each
  inference; the seed is an explicit, recorded parameter — never implicit.
- The model is loaded in `eval()` mode (config dropout 0.2 disabled); the
  wrapper raises if switched to train mode.
- Under this contract, outputs are bit-identical across processes for the
  same `(input_ids, ref_s, speed, seed)`. The HnNSF vocoder is stochastic by
  design (`torch.rand` `istftnet.py:150`; `torch.randn_like` `:205`, `:253`);
  the seed is what controls it.

## Deliberately excluded (stays upstream of this module)

Text, narration cleanup, normalization, eSpeak-NG phonemization, IPA → token
IDs (vocabulary lookup), `0` boundary padding, model-safe chunking, and
voice-row selection. None of it appears in the module or its graph path.

## Tensor-path decision

The wrapper reuses the pinned tensor path rather than rebuilding it. The
pinned `KModelForONNX.forward` (`kokoro/model.py:139`) is a one-line
delegation to `KModel.forward_with_tokens` and hides nothing — no vocab
lookup, string handling, or row selection in its path. Calling
`forward_with_tokens` directly keeps the module graph equal to one `KModel`,
so `tensor_path` is the export surface for task 2.2 with no redundant shell
and zero re-implemented ops.

## Bundle checksums (epoch-005, verified at construction)

| file | SHA-256 |
|---|---|
| `kokoro_dragana_sr.pth` (`model.pth` in the bundle manifest) | `4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47` |
| `sr_dragana.pt` (`voice.pt` in the bundle manifest) | `0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a` |

A mismatch raises before any inference can happen.

## Verification

```bash
cd model-tools
.venv/bin/python scripts/verify_export_wrapper.py
```

Runs all 7 vectors from `reference/vectors.json` (seed 20260826,
single-threaded, speed 1.0) and compares each output against the committed
reference WAV: sample count, PCM16 max abs diff, byte-for-byte WAV
identity. Expected: `ok: true` with `pcm_max_abs_diff: 0` on every vector
(bit-identical, same runtime/seed/threading as the capture). Any nonzero
diff fails the run — do not loosen it.

## Notes for task 2.2 (ONNX export manifest)

- Export `tensor_path` (or an equivalent alias): it contains no validation,
  seeding, or Python control flow. `forward`'s checks are desktop-only.
- The graph will contain RandomUniform/RandomNormal ops (from the vocoder's
  `torch.rand`/`torch.randn_like`). ONNX Runtime cannot seed these, so ONNX
  output is not bit-reproducible across runs; parity must use the statistical
  metrics of design §11, or the graph must later be patched with a seeded PRNG.
- Output length is data-dependent (`torch.repeat_interleave` over
  `pred_dur`) → declare a dynamic output axis.
- The seed is not a graph input; it is a desktop call-path control only.

## ONNX export & interface manifest (task 2.2)

The candidate `model-tools/export/dragana.onnx` (FP32, ~326 MB) is produced by
`scripts/export_onnx.py` and is **not committed**; its identity lives in
`model-tools/export/manifest.json` (`onnx_sha256`,
`f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6`).

- **Exporter** — legacy TorchScript exporter (`torch.onnx.export`, `dynamo=False`),
  torch 2.13.0, `onnx 1.22.0`, `onnxruntime 1.29.0`.
- **Route (Option A, PM decision)** — load the pinned `KModelForONNX` with
  `disable_complex=True` (CustomSTFT) + eager BERT attention, then trace. The
  dynamo/`torch.export` exporter is rejected because it cannot carry the
  model's data-dependent (unbacked) sequence length `sum(pred_dur)` through the
  F0Ntrain shared LSTM (`torch.export` needs a specialized integer there and
  fails — "Could not extract specialized integer"). The official Kokoro export
  (`kokoro-training/examples/export.py`) uses exactly this legacy+CustomSTFT
  route and yields a length-general graph.
- **Opset** — pinned **18** (`ai.onnx`). Rationale: broadest support across
  onnxruntime 1.29 desktop and the ORT Android builds, and it is the default target
  opset of the torch 2.13.0 legacy exporter's TorchScript ONNX symbolic functions
  for every op this graph uses (LSTM-7, RandomNormal/RandomUniform,
  ScatterND/ScatterElements, Resize-10, Pad-11/13, Conv/ConvTranspose-11, Erf…).
  The official Kokoro export targets 17; 18 adds no unsupported op here and is
  pinned for a wider-tested ONNX surface.
- **Dynamic axes** — **classic** `dynamic_axes` (this pinned ORT/torch line has no
  named dynamic-axes manifest step for the legacy exporter): `input_ids` axis 1
  = `seq_len` [2, 512]; `waveform` axis 0 = `waveform_len` (data-dependent,
  `300 * sum(pred_dur)`); `pred_dur` axis 0 = `pred_dur_len` (== input seq length).

Interface (FP32 asserted on graph I/O):

| direction | name | dtype | shape | axis |
|---|---|---|---|---|
| input | `input_ids` | int64 | `[1, seq_len]` | `seq_len` = L+2, [2, 512] |
| input | `ref_s` | float32 | `[1, 256]` | static |
| input | `speed` | float32 | scalar (0-D) | — |
| output | `waveform` | float32 | `[waveform_len]` | 24 kHz mono PCM |
| output | `pred_dur` | int64 | `[pred_dur_len]` | per-token frames, ≥ 1 |

### Length generality (hard gate)

The graph must be length-general, not baked to the export example. The ORT
session is run on all 7 reference vectors (6 distinct lengths) and **every**
sample count must match the PyTorch reference exactly; otherwise the script
fails. Verified 2026-08-27 (this desktop): all 7 match — 124200, 117600,
114600, 114000, 80400, 78000 samples.

### CustomSTFT deviation (measured drift)

**The exported graph's vocoder STFT is a KNOWN LOSSY approximation.** The
reference wrapper uses the exact `TorchSTFT` (`torch.stft`/`torch.istft`).
`CustomSTFT` is a conv1d/conv_transpose1d real-arithmetic reconstruction that
skips the DC/Nyquist doubling. This is the official Kokoro export route and the
deviation is **accepted and recorded** here: parity is statistical by design
(design §11) because the vocoder RNG is unseedable in ORT anyway.

Per-vector drift, ORT (CustomSTFT) vs PyTorch exact reference
(`forward_with_tokens`, speed 1.0, seed 20260826, single-threaded), from the
2026-08-27 re-run that also produced the committed `manifest.json`:

| vector | samples | max \|Δ\| | mean \|Δ\| | cosine | HF Δ (1st-diff) |
|---|---|---|---|---|---|
| greeting-latin | 124200 ✓ | 0.16076 | 0.038766 | 0.80146 | 0.031947 |
| greeting-cyrillic | 124200 ✓ | 0.16217 | 0.038763 | 0.80156 | 0.031339 |
| diacritics-latin | 114000 ✓ | 0.18534 | 0.036185 | 0.80183 | 0.029670 |
| diacritics-cyrillic | 114600 ✓ | 0.16728 | 0.036099 | 0.80280 | 0.031528 |
| mixed-digits | 117600 ✓ | 0.15445 | 0.039436 | 0.80438 | 0.035443 |
| punctuation | 80400 ✓ | 0.16886 | 0.038210 | 0.79766 | 0.039093 |
| cyrillic-punctuation | 78000 ✓ | 0.19804 | 0.038013 | 0.79864 | 0.027547 |

(These exact-reference figures shift run-to-run by roughly 1e-2 because the
vocoder `RandomNormal`/`RandomUniform` is **unseedable in ORT**; the committed
`manifest.json` records one such run and the `.onnx` binary SHA is stable
across runs.)

The exact-reference cosine is ~0.80 and max abs diff ~0.15–0.20 — this is the
expected **CustomSTFT + vocoder-RNG baseline drift** and does not gate the
candidate. The sanity floor that **does** gate uses the matching export-path
(ORT vs PyTorch CustomSTFT, same vocab-RNG class), isolating ONNX conversion
fidelity: sample-count match on every vector, all outputs finite, cosine > 0.9
per vector. Verified: export-path cosine 0.9975–0.9983, all finite, all lengths
match, `pred_dur` bit-identical on every vector.

Fallbacks **if task 2.6 later deems the drift unacceptable** (design §11, risks
register "unsupported/numerically unstable export ops"): the time-boxed
Sherpa-ONNX experiment (task 2.7), or a graph-level STFT patch that replaces the
CustomSTFT subgraph with an exact-TorchSTFT-equivalent realization. Formal parity
thresholds are defined and frozen separately (task 2.4, `parity/fp32-thresholds-v1.json`);
nothing here is a formal acceptance threshold.

## Validation report (task 2.3)

Validate the existing candidate without re-exporting it:

```bash
model-tools/.venv/bin/python model-tools/scripts/validate_onnx.py
```

The command writes `model-tools/export/validation.json` and emits the same
machine-readable report on stdout. It runs `onnx.checker`, creates a CPU
ONNX Runtime session, verifies the manifest contract, enumerates every unique
operator type and every initializer (shape, dtype, element count, storage
bytes, and external-data metadata), records the manifest's declared input
limits, and measures isolated-process RSS after session creation and after
representative and maximum-declared-input inference. The current report has
no external initializer data. The memory values are an observed desktop
runtime footprint, not the Android device qualification gate.

This validation intentionally does not apply waveform parity thresholds;
those are defined by task 2.4.

## FP32 parity thresholds (task 2.4)

`parity/fp32-thresholds-v1.json` is the frozen, versioned declaration for the
future parity runner. It is loaded before candidate vector evaluation by the
export script; no runtime threshold override is permitted. A threshold change
requires a new `thresholds_version` and review before evaluation.

The required gate covers every vector and fails closed: exact sample count,
pointwise waveform mean/max absolute error, flattened Hann-windowed STFT
magnitude cosine similarity, whole-output silence, full-scale clipping, and
non-finite values. The declaration records units, formulas, per-vector and
worst-case aggregation, strict comparator semantics, the 24 kHz mono float32
contract, and the PyTorch `CustomSTFT` comparison baseline. It does not run the
parity comparison; that is task 2.5.

Validate the schema and frozen policy without evaluating a candidate:

```bash
model-tools/.venv/bin/python model-tools/scripts/validate_parity_thresholds.py
```
