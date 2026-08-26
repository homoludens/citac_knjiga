"""Deterministic export wrapper for the Dragana Kokoro-82M tensor path (task 2.1).

Exposes the verified tensor boundary required by the design
(`build-serbian-audiobook-mvp` decision 2): token IDs + one selected Dragana
style row + speed in; 24 kHz float32 PCM (+ per-token predicted durations)
out. Nothing else crosses the boundary: no phoneme strings, no text, no
tokenization, no voice-row selection, no eSpeak. Row selection
(`min(len(ipa), 509)`) stays with the caller and is re-implemented in Kotlin.

Tensor-path decision: the wrapper reuses the pinned tensor path instead of
rebuilding it. The pinned `KModelForONNX.forward(input_ids, ref_s, speed)`
(kokoro/model.py:139) is a one-line delegation to
`KModel.forward_with_tokens` and hides nothing (no vocab lookup, no string
handling, no row selection, no side channels), so wrapping it or calling
`forward_with_tokens` are operationally identical. The wrapper calls
`forward_with_tokens` directly so the module graph contains the KModel
exactly once and task 2.2 can export `tensor_path` without a redundant shell
or any re-implemented ops.

Shape note: the pinned slicing `ref_s[:, 128:]` / `ref_s[:, :128]`
(kokoro/model.py:104,118) requires `ref_s` to be a 2-D row `[1, 256]`; a
3-D `[1, 1, 256]` row slices to `[1, 0, 256]` and fails inside
DurationEncoder (verified 2026-08-26 with the epoch-005 bundle).

Determinism contract (model-io.md §9):
  - the CALLER sets `torch.set_num_threads(1)` once at process start;
  - `forward` applies `torch.manual_seed(seed)` immediately before inference;
    the seed is an explicit, recorded parameter of the call path;
  - the model is loaded in `eval()` mode (config dropout 0.2 disabled).
The HnNSF vocoder is stochastic by design (torch.rand / torch.randn_like,
istftnet.py:150/205/253); under the contract above, outputs are
bit-identical across processes for the same (input_ids, ref_s, speed, seed).
The random nodes remain in the exported ONNX graph — task 2.2 must handle
them (ORT RandomNormal/RandomUniform are unseedable).
"""
from __future__ import annotations

import hashlib
import math
import sys
from pathlib import Path

# --- Pinned runtime (see model-tools/runtime-pins.md) -----------------------
KOKORO_RUNTIME = Path(
    "/home/homoludens/projekti/kokoro_tts_srpski_2/workspace/kokoro-serbian/"
    "runtime/upstream/kokoro-training"
)
DEFAULT_BUNDLE = Path(__file__).resolve().parents[2] / "kokoro_sr_dragana_voice"

sys.path.insert(0, str(KOKORO_RUNTIME))  # must precede `import kokoro`

import torch  # noqa: E402
from kokoro import KModel  # noqa: E402

# epoch-005 bundle identity (runtime-pins.md §4; reference/vectors.json)
EXPECTED_MODEL_SHA256 = "4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47"
EXPECTED_VOICE_SHA256 = "0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a"

# Verified model I/O (model-tools/model-io.md)
VOCAB_SIZE = 178      # config.json n_token; valid token IDs 0..177
MAX_INPUT_TOKENS = 512  # BERT max_position_embeddings; L+2 <= 512 (<= 510 phonemes)
MIN_INPUT_TOKENS = 2  # at least the two 0 boundary tokens
VOICE_ROWS = 510
STYLE_DIM = 256
SAMPLE_RATE = 24_000


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _verify_sha256(path: Path, expected: str, what: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"{what} file not found: {path}")
    actual = _sha256(path)
    if actual != expected:
        raise ValueError(f"{what} checksum mismatch: expected {expected}, got {actual} ({path})")


def _validate_inputs(
    input_ids: torch.Tensor, ref_s: torch.Tensor, speed: float, seed: int
) -> float:
    if not torch.is_tensor(input_ids):
        raise TypeError(f"input_ids must be a torch.Tensor, got {type(input_ids).__name__}")
    if input_ids.dtype != torch.int64:
        raise TypeError(f"input_ids must be int64 (LongTensor), got {input_ids.dtype}")
    if input_ids.device.type != "cpu":
        raise TypeError(f"input_ids must be on cpu, got {input_ids.device}")
    if input_ids.ndim != 2 or input_ids.shape[0] != 1:
        raise ValueError(f"input_ids must have shape [1, L+2], got {tuple(input_ids.shape)}")
    n_tokens = input_ids.shape[1]
    if not MIN_INPUT_TOKENS <= n_tokens <= MAX_INPUT_TOKENS:
        raise ValueError(
            f"input_ids length L+2 must be in [{MIN_INPUT_TOKENS}, {MAX_INPUT_TOKENS}] "
            f"(hard model limit: 510 phonemes), got {n_tokens}"
        )
    lo, hi = int(input_ids.min()), int(input_ids.max())
    if lo < 0 or hi >= VOCAB_SIZE:
        raise ValueError(f"input_ids values must be in [0, {VOCAB_SIZE - 1}], got [{lo}, {hi}]")
    if int(input_ids[0, 0]) != 0 or int(input_ids[0, -1]) != 0:
        raise ValueError("input_ids must begin and end with the 0 boundary token")
    if not torch.is_tensor(ref_s):
        raise TypeError(f"ref_s must be a torch.Tensor, got {type(ref_s).__name__}")
    if ref_s.dtype != torch.float32:
        raise TypeError(f"ref_s must be float32, got {ref_s.dtype}")
    if ref_s.device.type != "cpu":
        raise TypeError(f"ref_s must be on cpu, got {ref_s.device}")
    if tuple(ref_s.shape) != (1, STYLE_DIM):
        raise ValueError(
            f"ref_s must have shape [1, {STYLE_DIM}] (one selected voice row), "
            f"got {tuple(ref_s.shape)}"
        )
    if not bool(torch.isfinite(ref_s).all()):
        raise ValueError("ref_s must contain only finite values")
    if isinstance(speed, bool) or not isinstance(speed, (int, float)):
        raise TypeError(f"speed must be a positive float, got {type(speed).__name__}")
    speed = float(speed)
    if not math.isfinite(speed) or speed <= 0.0:
        raise ValueError(f"speed must be a positive finite number, got {speed!r}")
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise TypeError(f"seed must be an int, got {type(seed).__name__}")
    if seed < 0:
        raise ValueError(f"seed must be a non-negative int, got {seed}")
    return speed


class DraganaExportWrapper(torch.nn.Module):
    """Checksum-verified, deterministic tensor boundary for the epoch-005 bundle.

    Call path (verified tensors only)::

        waveform, pred_dur = wrapper(input_ids, ref_s, speed, seed=SEED)

    Inputs:
      input_ids : int64   [1, L+2]    L+2 in [2, 512], values 0..177, 0 boundary
                                      tokens at both ends
      ref_s     : float32 [1, 256]    one already-selected Dragana style row
                                      (row selection is the caller's job)
      speed     : float   > 0, finite
      seed      : int     >= 0; torch.manual_seed(seed) is applied immediately
                                      before inference

    Outputs:
      waveform  : float32 [N]         mono 24 kHz, magnitude < 1.0
      pred_dur  : int64   [L+2]       per-token predicted frame counts (>= 1)
    """

    def __init__(self, bundle_dir: str | Path | None = None, device: str = "cpu"):
        super().__init__()
        bundle = Path(bundle_dir) if bundle_dir is not None else DEFAULT_BUNDLE
        _verify_sha256(bundle / "kokoro_dragana_sr.pth", EXPECTED_MODEL_SHA256, "model")
        _verify_sha256(bundle / "sr_dragana.pt", EXPECTED_VOICE_SHA256, "voice")
        self.kmodel = KModel(
            repo_id="hexgrad/Kokoro-82M",
            config=str(bundle / "config.json"),
            model=str(bundle / "kokoro_dragana_sr.pth"),
        ).to(device).eval()
        voice = torch.load(bundle / "sr_dragana.pt", map_location="cpu", weights_only=True)
        if voice.dtype != torch.float32 or tuple(voice.shape) != (VOICE_ROWS, 1, STYLE_DIM):
            raise ValueError(
                f"voice table must be float32 [{VOICE_ROWS}, 1, {STYLE_DIM}], "
                f"got {voice.dtype} {tuple(voice.shape)}"
            )
        self.register_buffer("voice", voice)
        self._device = device
        self.eval()

    @property
    def voice_table(self) -> torch.Tensor:
        # Full Dragana style table, float32 [510, 1, 256]. Row selection
        # (row_index = min(len(ipa), 509)) is the caller's responsibility.
        return self.voice

    def tensor_path(
        self, input_ids: torch.Tensor, ref_s: torch.Tensor, speed: float
    ) -> tuple[torch.Tensor, torch.Tensor]:
        # Pure tensor path, identical to the pinned KModelForONNX.forward.
        # No validation, no seeding, no non-tensor control flow: this is the
        # surface task 2.2 exports to ONNX.
        return self.kmodel.forward_with_tokens(input_ids, ref_s, speed)

    def forward(
        self,
        input_ids: torch.Tensor,
        ref_s: torch.Tensor,
        speed: float,
        *,
        seed: int,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        speed = _validate_inputs(input_ids, ref_s, speed, seed)
        if self.training:
            raise RuntimeError("wrapper must be in eval() mode (dropout must stay disabled)")
        if torch.get_num_threads() != 1:
            raise RuntimeError(
                "bit-identity contract requires torch.set_num_threads(1) "
                "once at process start"
            )
        torch.manual_seed(seed)
        with torch.inference_mode():
            waveform, pred_dur = self.tensor_path(input_ids, ref_s, speed)
        if (
            waveform.ndim != 1
            or waveform.dtype != torch.float32
            or waveform.numel() == 0
            or not bool(torch.isfinite(waveform).all())
        ):
            raise RuntimeError(
                f"model returned an invalid waveform: {tuple(waveform.shape)} {waveform.dtype}"
            )
        if pred_dur.ndim != 1 or pred_dur.dtype != torch.int64 or int(pred_dur.min()) < 1:
            raise RuntimeError(
                f"model returned an invalid pred_dur: {tuple(pred_dur.shape)} {pred_dur.dtype}"
            )
        return waveform, pred_dur