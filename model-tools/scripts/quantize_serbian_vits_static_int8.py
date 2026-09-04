#!/usr/bin/env python3
"""Create an experimental static-QDQ INT8 Serbian VITS ONNX model."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import tempfile
from typing import Iterator

import numpy
import onnx
from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
from onnxruntime.quantization.shape_inference import quant_pre_process


CALIBRATION_TEXTS = (
    "Čitač knjiga govori jasno i prirodno.",
    "Добро дошли у библиотеку.",
    "Ovo je kratak primer srpskog teksta.",
    "Књига има поглавља, реченице и дијалоге.",
    "Dragana čita pažljivo, mirno i razgovetno.",
    "Нови ред почиње после завршене реченице.",
)
SCALES = (
    (0.667, 1.0, 0.8),
    (0.5, 0.9, 0.7),
    (0.8, 1.1, 0.9),
)


def load_tokens(path: Path) -> dict[str, int]:
    tokens: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.isdecimal():
            tokens[" "] = int(line)
            continue
        symbol, separator, token_id = line.rpartition(" ")
        if not separator:
            raise ValueError(f"invalid token entry: {line!r}")
        tokens[symbol] = int(token_id)
    if "<BLNK>" not in tokens:
        raise ValueError("tokens.txt does not declare <BLNK>")
    return tokens


def encode(text: str, tokens: dict[str, int]) -> numpy.ndarray:
    missing = sorted({character for character in text if character not in tokens})
    if missing:
        raise ValueError(f"calibration text has unsupported characters: {''.join(missing)!r}")
    blank = tokens["<BLNK>"]
    ids = [blank]
    for character in text:
        ids.extend((tokens[character], blank))
    return numpy.asarray([ids], dtype=numpy.int64)


class VitsCalibrationDataReader(CalibrationDataReader):
    def __init__(self, tokens: dict[str, int]) -> None:
        self.items = iter(
            {
                "input": encoded,
                "input_lengths": numpy.asarray([encoded.shape[1]], dtype=numpy.int64),
                "scales": numpy.asarray(scales, dtype=numpy.float32),
                "sid": numpy.asarray([0], dtype=numpy.int64),
                "langid": numpy.asarray([0], dtype=numpy.int64),
            }
            for text in CALIBRATION_TEXTS
            for scales in SCALES
            for encoded in (encode(text, tokens),)
        )

    def get_next(self) -> dict[str, numpy.ndarray] | None:
        return next(self.items, None)


def quantize(model: Path, tokens: Path, output: Path) -> None:
    if not model.is_file() or not tokens.is_file():
        raise ValueError("model.onnx and tokens.txt must exist")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="serbian-vits-int8-") as directory:
        preprocessed = Path(directory) / "model.preprocessed.onnx"
        previous_directory = Path.cwd()
        try:
            os.chdir(directory)
            # VITS duration prediction has dynamic random shapes that ORT cannot
            # resolve symbolically; standard ONNX shape inference still runs.
            quant_pre_process(model, preprocessed, skip_symbolic_shape=True)
        finally:
            os.chdir(previous_directory)
        quantize_static(
            preprocessed,
            output,
            VitsCalibrationDataReader(load_tokens(tokens)),
            quant_format=QuantFormat.QDQ,
            op_types_to_quantize=["Conv"],
            per_channel=True,
            activation_type=QuantType.QUInt8,
            weight_type=QuantType.QInt8,
        )
    graph = onnx.load(output, load_external_data=False)
    onnx.checker.check_model(graph)
    if any(initializer.external_data for initializer in graph.graph.initializer):
        raise RuntimeError("quantization produced external ONNX data")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path, help="FP32 model.onnx")
    parser.add_argument("tokens", type=Path, help="matching tokens.txt")
    parser.add_argument("output", type=Path, help="static-QDQ INT8 output path")
    args = parser.parse_args()
    quantize(args.model, args.tokens, args.output)
    print(json.dumps({"ok": True, "model": str(args.output), "calibration_samples": len(CALIBRATION_TEXTS) * len(SCALES)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
