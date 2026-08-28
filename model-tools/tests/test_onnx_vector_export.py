from __future__ import annotations

from pathlib import Path
import struct
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.export_onnx_vectors import write_float_wav  # noqa: E402


def test_export_writes_raw_float32_mono_wav(tmp_path: Path) -> None:
    path = tmp_path / "audio" / "vector.wav"
    path.parent.mkdir()
    pcm = np.asarray([0.25, -0.5, 0.75], dtype=np.float32)

    write_float_wav(path, pcm, 24_000)
    data = path.read_bytes()

    assert data[:4] == b"RIFF"
    assert data[8:12] == b"WAVE"
    assert struct.unpack_from("<H", data, 20)[0] == 3
    assert struct.unpack_from("<H", data, 22)[0] == 1
    assert struct.unpack_from("<I", data, 24)[0] == 24_000
    assert np.frombuffer(data, dtype="<f4", offset=44).tolist() == pcm.tolist()
