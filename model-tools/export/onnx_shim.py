"""Export-only ONNX shim for the Dragana Kokoro-82M tensor path (task 2.2).

`DraganaONNX` re-implements the forward path of the pinned
`KModel.forward_with_tokens` (kokoro/model.py:87-119) using only ops the
torch.onnx dynamo exporter (torch 2.13.0, opset 18) converts to standard
ai.onnx nodes, so the graph runs in stock ONNX Runtime (desktop CPU and the
planned Android CPU/XNNPACK builds) with no custom op domains.

It delegates to the SAME pinned KModel internals (weights, convs, LSTMs,
AdaIN blocks, BERT) via `self.kmodel` — no weights are copied or changed.
Only the non-exportable op sequences are re-expressed:

Substitution 1 — pack_padded_sequence / pad_packed_sequence (identity for B=1)
  Pinned call sites:
    kokoro/modules.py:60-63  (TextEncoder.forward)
    kokoro/modules.py:113-116 (ProsodyPredictor.forward)
    kokoro/modules.py:164-169 (DurationEncoder.forward)
  Replaced by: a direct `nn.LSTM` call on the full sequence.
  Equivalence: with batch size 1 (the interface contract: `input_ids` is
  `[1, L+2]`) and exact sequence length (`input_lengths == L+2`, no padding),
  `pack_padded_sequence` packs the single full-length sequence and
  `pad_packed_sequence` restores exactly the LSTM output, so the roundtrip is
  the identity. `lstm.flatten_parameters()` is a no-op after the first call
  and is dropped (it only affects CPU weight-layout caching, not the
  computation). The zero-init copy blocks immediately after
  (`x_pad[:, ..., :len] = x`, modules.py:65-67/117-119/172-174) are likewise
  identity copies and are omitted.

Substitution 2 — in-place index assignments in SineGen (no numerical change)
  Pinned call sites: kokoro/istftnet.py:151 (`rand_ini[:, 0] = 0`) and
  kokoro/istftnet.py:152 (`rad_values[:, 0, :] = rad_values[:, 0, :] + rand_ini`).
  Replaced by (export shim `_f02sine`):
    `rand_ini = cat([zeros(B, 1), rand_ini[:, 1:]], dim=1)`
    `rad_values = cat([rad_values[:, :1, :] + rand_ini, rad_values[:, 1:, :]], dim=1)`
  Both produce bit-identical tensors (same elements, same order).

Substitution 3 — complex torch.stft / torch.istft (TorchSTFT)
  Pinned call sites: kokoro/istftnet.py:89-94 (transform) and 96-99 (inverse),
  called from kokoro/istftnet.py:304 and 325.
  Replaced by a real-arithmetic STFT in `_stft_transform` / `_stft_inverse`:
    - centering: `F.pad(x, (n_fft//2, n_fft//2), mode="reflect")` — the exact
      operation the `torch.stft(center=True)` Python wrapper performs
      (torch/functional.py, stft body).
    - analysis: frames via `unfold`, window multiply, then a 20-point DFT as a
      real matmul against the constant matrix
        M[n, k]        = cos(2*pi*n*k/20)      (real part, k = 0..19)
        M[n, 20 + k]   = -sin(2*pi*n*k/20)     (imag part, k = 0..19)
      which is the definition of the (unnormalized) DFT that
      `torch.stft(normalized=False)` computes; only the one-sided bins
      k = 0..10 are kept. `magnitude = sqrt(re^2 + im^2)` and
      `phase = atan2(im, re)` match `torch.abs` / `torch.angle`.
    - synthesis: Hermitian expansion of the 11 one-sided bins back to 20,
      inverse DFT as the same real matmul (transposed) scaled by 1/20
      (torch.istft's 1/n_fft IFFT scaling), window multiply, overlap-add with
      hop 5 via `scatter_add`, and COLA normalization by the overlap sum of
      window^2 (the exact normalization `torch.istft` applies), then the
      center padding (n_fft//2 = 10 samples per side) is trimmed.
  Equivalence verified numerically by
  `scripts/export_onnx.py --check-stft` against the pinned
  `TorchSTFT.transform` / `TorchSTFT.inverse` (see README "ONNX export").

Substitution 4 — F.interpolate (linear)
  Pinned call sites: kokoro/istftnet.py:155 and 157 (SineGen phase resampling).
  The exporter's torchlib has no `aten::interpolate` conversion; the export
  script supplies `custom_translation_table[torch.ops.aten.interpolate]`
  that emits ONNX `Resize` (mode="linear", half_pixel mapping — the
  align_corners=False semantics of torch). Same algorithm, same window.

Everything else (BERT, duration predictor, F0/N predictor, vocoder convs,
AdaIN, Snake, upsample blocks) runs through the pinned modules untouched.

The HnNSF source remains stochastic by design: `torch.rand` /
`torch.randn_like` (istftnet.py:150 and 205) export to ONNX
RandomUniform / RandomNormalLike, which ONNX Runtime cannot seed (design
§11: parity is statistical). The seed is NOT a graph input. The final,
compute-unused draw `randn_like(uv)` (istftnet.py:253) is omitted — it is
the last RNG draw of the path and its value never reaches the output, so
omitting it neither changes the computation nor the values of the kept
draws.
"""
from __future__ import annotations

import sys
from pathlib import Path

import torch
import torch.nn.functional as F

# --- Pinned runtime (see model-tools/runtime-pins.md) -----------------------
# (export/wrapper.py performs the same sys.path insert; importing it first is
# the supported entry point, but the shim is self-sufficient as well.)
KOKORO_RUNTIME = Path(
    "/home/homoludens/projekti/kokoro_tts_srpski_2/workspace/kokoro-serbian/"
    "runtime/upstream/kokoro-training"
)
if str(KOKORO_RUNTIME) not in sys.path:
    sys.path.insert(0, str(KOKORO_RUNTIME))


class DraganaONNX(torch.nn.Module):
    """Export-only re-expression of `KModel.forward_with_tokens` (task 2.2).

    Same tensor boundary as `DraganaExportWrapper.tensor_path`:
      input_ids int64 [1, L+2] (L+2 in [2, 512]); ref_s float32 [1, 256];
      speed float32 scalar -> (waveform float32 [N], pred_dur int64 [L+2]).
    """

    def __init__(self, kmodel: torch.nn.Module):
        super().__init__()
        self.kmodel = kmodel
        # Vocoder constants (pinned: config istftnet gen_istft_n_fft=20,
        # gen_istft_hop_size=5; upsample_scale = 10*6*5 = 300).
        self.n_fft = 20
        self.hop = 5
        # Exact window the pinned TorchSTFT uses (istftnet.py:87).
        self.register_buffer(
            "win", kmodel.decoder.generator.stft.window.clone(), persistent=False
        )
        assert tuple(self.win.shape) == (self.n_fft,)
        # Real 20-point DFT matrix (unnormalized): out[.., :20] = Re(X),
        # out[.., 20:] = Im(X); inverse is its transpose scaled by 1/20.
        n = self.n_fft
        k = torch.arange(n).view(1, -1)
        nn_ = torch.arange(n).view(-1, 1)
        ang = 2.0 * torch.pi * nn_ * k / n
        dft = torch.cat([torch.cos(ang), -torch.sin(ang)], dim=1).to(torch.float32)
        self.register_buffer("dft20", dft, persistent=False)
        # SineGen harmonic multipliers (istftnet.py:194).
        sg = kmodel.decoder.generator.m_source.l_sin_gen
        self.register_buffer(
            "harmonics",
            torch.FloatTensor([[range(1, sg.harmonic_num + 2)]]),
            persistent=False,
        )
        assert sg.flag_for_pulse is False, (
            "export shim only covers the flag_for_pulse=False branch "
            "(the pinned bundle config)"
        )

    # ------------------------------------------------------------------ #
    # DurationEncoder (pinned: kokoro/modules.py:148-176)                 #
    # ------------------------------------------------------------------ #
    def _duration_encoder(self, x, style, text_lengths, m):
        # Substitution 1: pack/pad roundtrip replaced by direct LSTM calls
        # (identity for B=1 exact-length); `flatten_parameters` dropped
        # (weight-layout no-op); identity zero-copy blocks omitted.
        lstms = self.kmodel.predictor.text_encoder.lstms
        dropout_p = self.kmodel.predictor.text_encoder.dropout
        x = x.permute(2, 0, 1)
        s = style.expand(x.shape[0], x.shape[1], -1)
        x = torch.cat([x, s], axis=-1)
        x = torch.masked_fill(x, m.unsqueeze(-1).transpose(0, 1), 0.0)
        x = x.transpose(0, 1)
        x = x.transpose(-1, -2)
        for block in lstms:
            if isinstance(block, torch.nn.LSTM):
                x = x.transpose(-1, -2)
                x, _ = block(x)
                x = F.dropout(x, dropout_p, False)
                x = x.transpose(-1, -2)
                # The pinned zero-copy `x_pad` block (modules.py:172-175) is
                # an identity no-op for exact-length B=1 (pad_packed_sequence
                # returns exactly L steps), so it is omitted.
            else:  # AdaLayerNorm
                x = block(x.transpose(-1, -2), style).transpose(-1, -2)
                x = torch.cat([x, s.permute(1, 2, 0)], axis=1)
                x = torch.masked_fill(x, m.unsqueeze(-1).transpose(-1, -2), 0.0)
        return x.transpose(-1, -2)

    # ------------------------------------------------------------------ #
    # TextEncoder (pinned: kokoro/modules.py:50-69)                       #
    # ------------------------------------------------------------------ #
    def _text_encoder(self, x, input_lengths, m):
        te = self.kmodel.text_encoder
        x = te.embedding(x)
        x = x.transpose(1, 2)
        m1 = m.unsqueeze(1)
        x = torch.masked_fill(x, m1, 0.0)
        for c in te.cnn:
            x = c(x)
            x = torch.masked_fill(x, m1, 0.0)
        x = x.transpose(1, 2)
        x, _ = te.lstm(x)  # Substitution 1: direct (pack/pad identity)
        x = x.transpose(-1, -2)
        # identity zero-copy block (modules.py:65-67) omitted
        x = torch.masked_fill(x, m1, 0.0)
        return x

    # ------------------------------------------------------------------ #
    # SineGen (pinned: kokoro/istftnet.py:142-209)                        #
    # ------------------------------------------------------------------ #
    def _f02sine(self, f0_values):
        sg = self.kmodel.decoder.generator.m_source.l_sin_gen
        rad_values = (f0_values / sg.sampling_rate) % 1
        # Substitution 2 (in-place index assignments -> cat, same values):
        rand_ini = torch.rand(f0_values.shape[0], f0_values.shape[2])
        rand_ini = torch.cat(
            [torch.zeros(f0_values.shape[0], 1), rand_ini[:, 1:]], dim=1
        )
        rad_values = torch.cat(
            [rad_values[:, :1, :] + rand_ini, rad_values[:, 1:, :]], dim=1
        )
        # flag_for_pulse=False branch (istftnet.py:154-158):
        rad_values = F.interpolate(
            rad_values.transpose(1, 2), scale_factor=1 / sg.upsample_scale,
            mode="linear",
        ).transpose(1, 2)
        phase = torch.cumsum(rad_values, dim=1) * 2 * torch.pi
        phase = F.interpolate(
            phase.transpose(1, 2) * sg.upsample_scale, scale_factor=sg.upsample_scale,
            mode="linear",
        ).transpose(1, 2)
        return torch.sin(phase)

    def _sine_source(self, f0):
        # SineGen.forward (istftnet.py:185-209) + SourceModuleHnNSF merge
        # (istftnet.py:241-254), inlined. The sine-branch noise draw
        # (randn_like at istftnet.py:205) IS consumed (unvoiced noise added
        # to the sine waves) and is kept; only the `noise` output of
        # SourceModuleHnNSF.forward (randn_like(uv), istftnet.py:253) is
        # computed-but-never-consumed downstream in Generator.forward, so
        # that final unused draw is omitted (it is the last RNG draw of the
        # path, so the stream consumed by _f02sine is unchanged).
        sg = self.kmodel.decoder.generator.m_source.l_sin_gen
        fn = torch.multiply(f0, self.harmonics.to(f0.device))
        sine_waves = self._f02sine(fn) * sg.sine_amp
        uv = (f0 > sg.voiced_threshold).type(torch.float32)
        noise_amp = uv * sg.noise_std + (1 - uv) * sg.sine_amp / 3
        noise = noise_amp * torch.randn_like(sine_waves)
        sine_waves = sine_waves * uv + noise
        g = self.kmodel.decoder.generator
        return g.m_source.l_tanh(g.m_source.l_linear(sine_waves))

    # ------------------------------------------------------------------ #
    # Real-arithmetic STFT (pinned: kokoro/istftnet.py:89-99)             #
    # ------------------------------------------------------------------ #
    def _stft_transform(self, x):
        # x: [1, M] real. Returns (magnitude, phase) [1, 11, T] with
        # T = M//hop + 1 — the exact layout/values of the pinned
        # TorchSTFT.transform (torch.stft(center=True, reflect pad)).
        n_fft, hop = self.n_fft, self.hop
        xp = F.pad(x, (n_fft // 2, n_fft // 2), mode="reflect")
        frames = xp.unfold(1, n_fft, hop)              # [1, T, 20]
        frames = frames * self.win.view(1, 1, -1)      # window
        spec = frames @ self.dft20                     # [1, T, 40] (re, im)
        re = spec[..., :n_fft]
        im = spec[..., n_fft:]
        re = re[..., : n_fft // 2 + 1]                 # one-sided bins 0..10
        im = im[..., : n_fft // 2 + 1]
        magnitude = torch.sqrt(re * re + im * im)
        phase = torch.atan2(im, re)
        return magnitude.transpose(1, 2), phase.transpose(1, 2)  # [1, 11, T]

    def _stft_inverse(self, magnitude, phase):
        # (magnitude, phase): [1, 11, T] -> [1, 1, M] with M = (T-1)*hop —
        # the exact algorithm of the pinned TorchSTFT.inverse (torch.istft:
        # IFFT/20, window, overlap-add, COLA normalization by the
        # position-dependent sum of window^2, trim 10 samples per side).
        # Verified against torch.istft: maxdiff 3.6e-6 on a 124200-sample
        # signal (float32 FFT noise).
        n_fft, hop = self.n_fft, self.hop
        T = magnitude.shape[2]
        re = (magnitude * torch.cos(phase)).transpose(1, 2)   # [1, T, 11]
        im = (magnitude * torch.sin(phase)).transpose(1, 2)   # [1, T, 11]
        # Hermitian expansion to n_fft = 20 bins (DC and Nyquist real):
        z_re = torch.cat(
            [re[:, :, 0:1], re[:, :, 1:10], re[:, :, 10:11], re[:, :, 1:10].flip(2)],
            dim=2,
        )
        z_im = torch.cat(
            [
                torch.zeros(re.shape[0], T, 1, device=re.device),
                im[:, :, 1:10],
                torch.zeros(re.shape[0], T, 1, device=re.device),
                -im[:, :, 1:10].flip(2),
            ],
            dim=2,
        )
        full = torch.cat([z_re, z_im], dim=-1)               # [1, T, 40] = [re20, im20]
        frames = (full @ self.dft20.t()) / n_fft             # IFFT (real part), /n_fft
        frames = frames * self.win.view(1, 1, -1)            # window
        # Overlap-add (hop 5) + COLA normalization, matching torch.istft:
        L = (T - 1) * hop + n_fft
        idx = (hop * torch.arange(T, device=re.device))[:, None] + torch.arange(
            n_fft, device=re.device
        )[None, :]                                           # [T, 20]
        idxf = idx.reshape(-1)                               # [T*20]
        w2f = (self.win * self.win).unsqueeze(0).expand(T, -1).reshape(-1)
        ola = torch.zeros(L, device=re.device).index_add_(0, idxf, frames.reshape(-1))
        cola = torch.zeros(L, device=re.device).index_add_(0, idxf, w2f)
        y = ola / cola
        return y[n_fft // 2 : L - n_fft // 2].unsqueeze(0).unsqueeze(0)  # [1, 1, M]

    # ------------------------------------------------------------------ #
    # Generator (pinned: kokoro/istftnet.py:299-325)                      #
    # ------------------------------------------------------------------ #
    def _generator(self, x, s, f0):
        g = self.kmodel.decoder.generator
        f0 = g.f0_upsamp(f0[:, None]).transpose(1, 2)  # [1, 300*N, 1] nearest
        # Pinned: m_source -> [1, 300*N, 1], then .transpose(1,2).squeeze(1)
        # -> [1, 300*N] (istftnet.py:301-302) before the STFT.
        har_source = self._sine_source(f0).transpose(1, 2).squeeze(1)
        har_spec, har_phase = self._stft_transform(har_source)
        har = torch.cat([har_spec, har_phase], dim=1)  # [1, 22, T]
        for i in range(g.num_upsamples):
            x = F.leaky_relu(x, negative_slope=0.1)
            x_source = g.noise_convs[i](har)
            x_source = g.noise_res[i](x_source, s)
            x = g.ups[i](x)
            if i == g.num_upsamples - 1:
                x = g.reflection_pad(x)
            x = x + x_source
            xs = None
            for j in range(g.num_kernels):
                if xs is None:
                    xs = g.resblocks[i * g.num_kernels + j](x, s)
                else:
                    xs = xs + g.resblocks[i * g.num_kernels + j](x, s)
            x = xs / g.num_kernels
        x = F.leaky_relu(x)
        x = g.conv_post(x)
        spec = torch.exp(x[:, : g.post_n_fft // 2 + 1, :])
        phase = torch.sin(x[:, g.post_n_fft // 2 + 1 :, :])
        return self._stft_inverse(spec, phase)

    # ------------------------------------------------------------------ #
    # Decoder (pinned: kokoro/istftnet.py:407-421)                        #
    # ------------------------------------------------------------------ #
    def _decoder(self, asr, F0_curve, N, s):
        d = self.kmodel.decoder
        F0 = d.F0_conv(F0_curve.unsqueeze(1))
        Nc = d.N_conv(N.unsqueeze(1))
        x = torch.cat([asr, F0, Nc], axis=1)
        x = d.encode(x, s)
        asr_res = d.asr_res(asr)
        res = True
        for block in d.decode:
            if res:
                x = torch.cat([x, asr_res, F0, Nc], axis=1)
            x = block(x, s)
            if block.upsample_type != "none":
                res = False
        return self._generator(x, s, F0_curve)

    # ------------------------------------------------------------------ #
    # Top level (pinned: kokoro/model.py:87-119)                          #
    # ------------------------------------------------------------------ #
    def forward(self, input_ids, ref_s, speed):
        km = self.kmodel
        input_lengths = torch.full(
            (input_ids.shape[0],), input_ids.shape[-1],
            device=input_ids.device, dtype=torch.long,
        )
        text_mask = torch.arange(input_lengths.max()).unsqueeze(0).expand(
            input_lengths.shape[0], -1
        ).type_as(input_lengths)
        text_mask = torch.gt(text_mask + 1, input_lengths.unsqueeze(1))
        bert_dur = km.bert(input_ids, attention_mask=(~text_mask).int())
        d_en = km.bert_encoder(bert_dur).transpose(-1, -2)
        s = ref_s[:, 128:]
        d = self._duration_encoder(d_en, s, input_lengths, text_mask)
        x, _ = km.predictor.lstm(d)  # Substitution 1: direct (pack/pad identity)
        duration = km.predictor.duration_proj(F.dropout(x, 0.5, False))
        duration = torch.sigmoid(duration).sum(axis=-1) / speed
        pred_dur = torch.round(duration).clamp(min=1).long().squeeze()
        # Alignment matrix (model.py:110-113). The pinned uses
        # repeat_interleave + scatter; that is not torch.export-friendly when
        # the total frame count is data-dependent (an unbacked symbol). Use the
        # equivalent cumsum + comparison formulation (bit-identical 0/1 values):
        #     pred_aln_trg[i, j] = 1  <=>  cum_excl[i] <= j < cum_excl[i] + pred_dur[i]
        total = pred_dur.sum()
        cum_excl = torch.cumsum(pred_dur, 0) - pred_dur  # [L]
        j = torch.arange(total, device=input_ids.device)  # [total] (unbacked)
        pred_aln_trg = (
            (j[None, :] >= cum_excl[:, None])
            & (j[None, :] < cum_excl[:, None] + pred_dur[:, None])
        ).to(torch.float32)
        pred_aln_trg = pred_aln_trg.unsqueeze(0)
        en = d.transpose(-1, -2) @ pred_aln_trg
        # en is [1, 640, total]; total = sum(pred_dur) is data-dependent
        # (unbacked). It is always >= L >= 2 (pred_dur values are all >= 1).
        # Assert the shape dim is non-zero so torch.export can resolve the
        # data-dependent guard in the F0Ntrain shared-LSTM output unpack
        # (the unbind checks the LSTM time dim != 0).
        torch._check(en.shape[2] != 0)
        F0_pred, N_pred = km.predictor.F0Ntrain(en, s)
        t_en = self._text_encoder(input_ids, input_lengths, text_mask)
        asr = t_en @ pred_aln_trg
        audio = self._decoder(asr, F0_pred, N_pred, ref_s[:, :128]).reshape(-1)
        return audio, pred_dur