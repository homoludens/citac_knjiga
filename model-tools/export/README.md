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
