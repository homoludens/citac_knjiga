# Model I/O Documentation — Dragana Kokoro-82M (task 1.3)

Everything below was read from the pinned runtime
(`semidark/kokoro@b96fef95`, `kokoro/model.py`, `kokoro/modules.py`,
`kokoro/istftnet.py`) and **verified empirically** on 2026-08-26 with the
locked `model-tools` venv (torch 2.13.0 CPU) against the epoch-005 bundle.

## 1. High-level interface

```
KModel.forward(phonemes: str, ref_s: FloatTensor[510,1,256], speed: float = 1.0)
    -> FloatTensor  # 1-D, mono, 24 kHz, float32, nominal range [-1, 1)
```

- `phonemes`: a *normalized IPA string* (output of
  `kokoro_sr.phonemes.phonemize_serbian`, i.e. eSpeak-NG `--ipa=3 -v sr` +
  `normalize_ipa`). Spaces are a real token (token 16). Punctuation in the
  string maps to prosody tokens (see §4).
- `ref_s`: the Dragana style table, shape **`[510, 1, 256]`** (510 rows ×
  1 × 256), dtype float32. One row is selected per call — see §5.
- `speed`: positive float. `>1` = faster. Scales predicted durations
  (`duration / speed`) before rounding (§6).
- `return_output=True` also returns `pred_dur` (per-phoneme frame counts).
- A ready-made ONNX export wrapper exists in the pinned runtime:
  `KModelForONNX.forward(input_ids: LongTensor[1, L+2], ref_s: FloatTensor[1,1,256], speed: float)
  -> (waveform, pred_dur)`. This is the natural Phase-2 export boundary:
  it exposes **token IDs + selected style row + speed** as inputs, exactly as
  the design decision requires (no hidden preprocessing in the graph).

## 2. Architecture (from config.json + model.py)

| Block | Config | Notes |
|---|---|---|
| `bert` | CustomAlbert (PL-BERT) `hidden_size=768`, 12 layers, 12 heads, intermediate 2048, `max_position_embeddings=512` | content encoder over token IDs |
| `bert_encoder` | `Linear(768 -> hidden_dim=512)` | |
| `predictor` | ProsodyPredictor `style_dim=128`, `d_hid=512`, `nlayers=3`, `max_dur=50` | duration + F0 + noise (N) prediction |
| `text_encoder` | TextEncoder `channels=512`, kernel 5, depth 3, `n_symbols=178` | convolutional token embedding |
| `decoder` | ISTFTNet HnNSF vocoder `dim_out=n_mels=80`, upsample rates [10,6], ISTFT hop 5 / n_fft 20 | waveform synthesis |

Config identity: `kokoro_sr_dragana_voice/config.json`
SHA-256 `5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f`.
Key scalars: `n_token=178`, `hidden_dim=512`, `style_dim=128`, `n_mels=80`,
`max_dur=50`, `dim_in=64`.

## 3. Tokenization (phoneme string -> token IDs)

`forward()` does exactly this (model.py:128-131):

```python
input_ids = [vocab.get(p) for p in phonemes]   # per *character*
input_ids = [i for i in input_ids if i is not None]  # unknown chars dropped
input_ids = torch.LongTensor([[0, *input_ids, 0]])   # pad both ends with 0
# -> shape [1, L+2], dtype int64 (LongTensor)
```

- Vocabulary: the 178-slot `vocab` map in config.json; **115 valid symbol
  entries** (IDs 1–178 with gaps; 0 = padding/BOS/EOS, 63 unused
  private-use placeholder slots).
- Consistency check verified: the curated `kokoro_sr.phonemes.KOKORO_SYMBOLS`
  set (115 symbols) is exactly the set of valid vocab keys.
- Token 0 is both the left and right boundary token.

## 4. Boundary/prosody tokens

Punctuation characters present in the IPA string map to fixed IDs
(config.json `vocab`): `;`=1 `:`=2 `,`=3 `.`=4 `!`=5 `?`=6 `—`=9 `…`=10
`"`=11 `(`=12 `)`=13 `“`=14 `”`=15 ` `(space)=16. Stress/diacritic marks:
`ˈ`=156 `ˌ`=157 `ː`=158 `ʰ`=162 `ʲ`=164; intonation arrows `↓`=169 `→`=171
`↗`=172 `↘`=173; tilde `̃`=17.

## 5. Voice / style lookup semantics (verified)

- `ref_s` has **510 rows**, each `[1, 256]`. The call selects **one row**:
  `row_index = min(len(ipa), 509)` — i.e. the row index follows the *phoneme
  count*, clamped at 509.
- Internally the 256-dim row is split:
  - `ref_s[:, :128]` → content/style vector consumed by the **decoder**
    (HnNSF source + ISTFTNet conditioning).
  - `ref_s[:, 128:]` → style vector `s` consumed by the **predictor**
    (duration, F0, N).
- So a full call is: *one token-ID sequence + one 256-dim style row + speed*.
  No other per-call conditioning exists.
- The row choice is a documented quirk of the export (style rows were
  precomputed per utterance length). For Android, the whole 510-row table is
  shipped and the same `min(len, 509)` rule must be reproduced exactly.

## 6. Duration / speed semantics

`predictor.duration_proj(x)` → `sigmoid(...).sum(-1) / speed` →
`round().clamp(min=1)` → integer frames per phoneme. Speed therefore scales
per-phoneme frame counts linearly, rounded to ≥1 frame. `max_dur=50` bounds
the per-phoneme frame prediction.

## 7. Sample rate & output format

- **24 000 Hz mono float32**, magnitude < 1.0 (verified: smoke test peak
  0.356; 507-phoneme probe peak 0.876 — no clipping, but near the rail).
- Duration is not fixed: `output_samples = 24000 * sum(pred_dur) / (10*6)`
  (upsample rates) — measured 124 200 samples (5.175 s) for the 67-phoneme
  smoke text; 611 400 samples (25.48 s) for 507 repeated phonemes.
- Development writes use PCM16 WAV (`soundfile`, `subtype="PCM_16"`).

## 8. Verified maximum-input rule (empirical)

`forward()` asserts `len(input_ids) + 2 <= context_length` with
`context_length = 512`. Verified on-device 2026-08-26
(`scripts/probe_max_input.py`):

| IPA length L | assert `L+2 ≤ 512` | voice row | real inference |
|---|---|---|---|
| 507 | pass | 507 | OK (25.48 s, peak 0.876) |
| 508 / 509 | pass | 508 / 509 | (assert only) |
| 510 | pass | **509 (clamped)** | OK (25.65 s) |
| 511 | **fail** | 509 | would raise AssertionError |

**Hard model limit: 510 phonemes per call. The reference `speak.py` uses a
conservative operational cap of 507.** Chunking (spec: serbian-text-processing)
must guarantee `len(normalized IPA) ≤ 507` per chunk to stay inside the
reference behavior; 508–510 would change the selected style row and are not
part of the known-good path.

## 9. Randomness controls (verified)

The network parts run in `eval()` (dropout disabled), but the **HnNSF source
is stochastic**: random initial phase (`torch.rand`, istftnet.py:150) and
additive Gaussian noise (`torch.randn_like`, istftnet.py:205/253).

Verified (`scripts/verify_determinism.py`), 67-phoneme text:

| Comparison | max abs diff | bit-identical | cosine sim |
|---|---|---|---|
| unseeded vs unseeded | 0.057 | no | 0.9967 |
| seed 1234 vs seed 1234 | **0.0** | **yes** | 1.0 |
| seed 1234 vs seed 9999 | 0.081 | no | 0.9967 |

**Control: `torch.manual_seed(seed)` immediately before each `model(...)`
call makes output bit-identical.** Consequences:

- Reference captures (task 1.4) are produced with a fixed, recorded seed.
- Phase-2 parity (PyTorch vs ONNX) must seed both sides identically; even
  then, expect small numerical drift, so parity metrics are *statistical*
  (sample count, waveform error, spectral similarity) — per design §11.
- The unvoiced-noise floor differs run-to-run; parity thresholds for
  silence/noise must account for that.

## 10. Performance anchors (this desktop, 8-core CPU, torch 2.13.0)

- Model load (checkpoint → eval): **21.75 s** (one-time).
- Inference, 67 phonemes: **2.69 s** for 5.175 s audio → RTF ≈ 0.52.
- Inference, 507 phonemes: **16.5 s** for 25.48 s audio → RTF ≈ 0.65.

These are desktop anchors only; the Poco F3 gate (Phase 5) is the binding
one.