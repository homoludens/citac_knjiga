#!/usr/bin/env python3
"""Verify the deterministic export wrapper against the reference vectors (task 2.1).

Loads the wrapper (checksum-verified epoch-005 bundle), runs every vector in
model-tools/reference/vectors.json through it with the recorded seed and
single-threaded inference, and compares each output sample-for-sample against
the committed reference WAV (PCM16). The wrapper's float32 output is
re-encoded with the same soundfile PCM_16 path the capture used, so a zero
diff means bit-identical end-to-end (same runtime, seed, and threading as the
capture). There is no tolerance to loosen: any mismatch exits non-zero and
the exact numbers are reported. Prints a machine-readable JSON result.
"""
from __future__ import annotations

import json
import sys
import tempfile
import time
from pathlib import Path

# Determinism contract (model-io.md §9): single-threaded CPU inference.
import torch  # noqa: E402

torch.set_num_threads(1)

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "model-tools"))  # for the export package

import numpy as np  # noqa: E402
import soundfile as sf  # noqa: E402

from export.wrapper import DraganaExportWrapper  # noqa: E402

BUNDLE = REPO / "kokoro_sr_dragana_voice"
VECTORS_JSON = REPO / "model-tools/reference/vectors.json"
SPEED = 1.0


def main() -> int:
    started = time.monotonic()
    meta = json.loads(VECTORS_JSON.read_text(encoding="utf-8"))
    seed = int(meta["seed"])
    if seed != 20260826 or int(meta["torch_num_threads"]) != 1:
        print("vectors.json does not match the documented reference (seed/threads)")
        return 1

    load_started = time.monotonic()
    wrapper = DraganaExportWrapper(BUNDLE)
    load_seconds = time.monotonic() - load_started
    voice = wrapper.voice_table

    vectors = []
    all_ok = True
    for v in meta["vectors"]:
        input_ids = torch.LongTensor([v["token_ids"]])
        ref_s = voice[v["voice_row_index"]]  # [1, 256]; row selection is the caller's job
        t0 = time.monotonic()
        waveform, pred_dur = wrapper(input_ids, ref_s, SPEED, seed=seed)
        infer_seconds = time.monotonic() - t0
        w = waveform.detach().cpu().numpy()
        ref_path = Path(v["wav"])
        ref_pcm, sr = sf.read(ref_path, dtype="int16")
        with tempfile.TemporaryDirectory() as td:
            out_wav = Path(td) / f"{v['id']}.wav"
            sf.write(out_wav, w, 24000, subtype="PCM_16")
            out_pcm, _ = sf.read(out_wav, dtype="int16")
            wav_byte_identical = out_wav.read_bytes() == ref_path.read_bytes()
        same_size = bool(w.size == ref_pcm.size == v["audio_samples"])
        if same_size:
            pcm_diff = np.abs(ref_pcm.astype(np.int64) - out_pcm.astype(np.int64))
            pcm_max_abs_diff = int(pcm_diff.max())
            bit_identical = bool(pcm_max_abs_diff == 0)
            float_max_abs_diff = float(np.max(np.abs(w - ref_pcm.astype(np.float32) / 32768.0)))
        else:
            pcm_max_abs_diff = -1
            bit_identical = False
            float_max_abs_diff = None
        ok = same_size and bit_identical and wav_byte_identical and bool(sr == 24000)
        all_ok = all_ok and ok
        vectors.append({
            "id": v["id"],
            "token_count": v["token_count"],
            "voice_row_index": v["voice_row_index"],
            "audio_samples_expected": int(v["audio_samples"]),
            "audio_samples": int(out_pcm.size),
            "sample_count_match": same_size,
            "sample_rate_ok": bool(sr == 24000),
            "pcm_max_abs_diff": pcm_max_abs_diff,
            "pcm_bit_identical": bit_identical,
            "wav_byte_identical": bool(wav_byte_identical),
            "float_max_abs_diff_vs_ref_pcm": float_max_abs_diff,
            "pred_dur_sum": int(pred_dur.sum()),
            "infer_seconds": round(infer_seconds, 2),
            "ok": ok,
        })
        print(f"  {v['id']:24s} tok={v['token_count']:3d} samples={out_pcm.size} "
              f"max_diff={pcm_max_abs_diff} bit_identical={bit_identical} ({infer_seconds:.2f}s)")

    result = {
        "seed": seed,
        "torch_num_threads": 1,
        "speed": SPEED,
        "torch_version": torch.__version__,
        "load_seconds": round(load_seconds, 2),
        "total_seconds": round(time.monotonic() - started, 2),
        "vectors": vectors,
        "ok": bool(all_ok),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())