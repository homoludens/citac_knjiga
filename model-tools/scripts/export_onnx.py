"""Export the Dragana ONNX candidate + interface manifest (task 2.2).

Route (PM decision, "Option A" — the official Kokoro route):
    legacy TorchScript exporter (torch.onnx.export, dynamo=False) of
    KModelForONNX loaded with ``disable_complex=True`` (-> CustomSTFT),
    opset 18, classic ``dynamic_axes``.

Rationale (recorded in README):
  * The dynamo (torch.export) exporter cannot carry the model's
    data-dependent (unbacked) sequence length ``sum(pred_dur)`` through the
    F0Ntrain shared LSTM — ``torch.export`` requires a specialized integer
    there and fails. This is a hard torch-export limitation, not a hint issue.
  * The official Kokoro ONNX export (kokoro-training examples/export.py) uses
    exactly this legacy + ``disable_complex=True`` route and produces a
    LENGTH-GENERAL graph (verified: 3 distinct input lengths all reproduce the
    exact PyTorch sample count).
  * ``CustomSTFT`` is a KNOWN, RECORDED lossy approximation of the exact
    TorchSTFT (DC/Nyquist doubling skipped). PyTorch-vs-ONNX parity is
    statistical by design §11 (the vocoder RNG is unseedable in ORT), so this
    lossiness is covered by the task-2.4 thresholds, not silently accepted.

Usage (from repo root):
    model-tools/.venv/bin/python model-tools/scripts/export_onnx.py

What it does:
  1. torch.set_num_threads(1) at process start (determinism contract).
  2. Loads the checksum-verified DraganaExportWrapper (epoch-005, exact
     TorchSTFT) — this is the PyTorch REFERENCE for drift measurement.
  3. Loads a SECOND KModel with disable_complex=True (CustomSTFT) + eager BERT
     attention — this is what gets EXPORTED.
  4. Legacy torch.onnx.export (dynamo=False, opset 18, classic dynamic_axes)
     of KModelForONNX.
  5. onnx.checker + programmatic dtype/shape boundary assertions (FP32).
  6. LENGTH-GENERALITY HARD GATE: runs the ORT session on all 7 reference
     vectors (>= 3 of distinct length) and REQUIRES exact sample-count match
     vs the PyTorch reference for every vector. If any length is baked in or
     mismatches, the script fails (Option A is dead -> revisit B/C).
  7. 7-VECTOR DRIFT: for every vector, record ORT (CustomSTFT) versus both the
     exact PyTorch reference and the PyTorch CustomSTFT export path (seed
     20260826, single-threaded). Record sample-count match, max abs diff, mean
     abs diff, cosine similarity, and high-frequency error (max abs diff of
     the first-order difference). The hard sanity floor is enforced against
     the matching export path (fail if violated): sample count matches on
     every vector, all outputs finite, cosine > 0.9. The exact-reference
     metrics remain evidence of the known CustomSTFT baseline drift.
     (Formal parity thresholds are task 2.4 — NOT defined here.)
  8. Writes model-tools/export/dragana.onnx (not committed) and
     model-tools/export/manifest.json (committed).
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path

import torch

torch.set_num_threads(1)  # determinism contract: once at process start

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "model-tools"))

from export.wrapper import (  # noqa: E402
    DraganaExportWrapper,
    EXPECTED_MODEL_SHA256,
    EXPECTED_VOICE_SHA256,
    MAX_INPUT_TOKENS,
    SAMPLE_RATE,
)
from parity.thresholds import load_thresholds  # noqa: E402
# Importing the wrapper also puts the pinned kokoro runtime on sys.path.
from kokoro import KModel  # noqa: E402
from kokoro.model import KModelForONNX  # noqa: E402

# --- pinned environment identity ------------------------------------------
import onnx  # noqa: E402
import onnxruntime  # noqa: E402
import onnxscript  # noqa: E402

OPSET_VERSION = 18
OPSET_RATIONALE = (
    "18: broadest support across onnxruntime 1.29 (desktop) and the ORT "
    "Android builds, and it is the default target opset of the torch 2.13.0 "
    "legacy exporter's TorchScript ONNX symbolic functions for every op this "
    "graph uses (LSTM-7, RandomNormal/RandomUniform, ScatterND/ScatterElements, "
    "Resize-10, Pad-11/13, Conv/ConvTranspose-11, Erf, etc.). The official "
    "Kokoro export targets 17; 18 adds no unsupported op for this graph and "
    "matches the wider-verified torchlib path, so we pin 18."
)
EXPORTER = (
    "torch.onnx.export(dynamo=False) — legacy TorchScript-based exporter "
    "(torch.onnx.utils._export), opset 18, classic dynamic_axes"
)

MANIFEST_PATH = REPO / "model-tools" / "export" / "manifest.json"
ONNX_PATH = REPO / "model-tools" / "export" / "dragana.onnx"
VECTORS_PATH = REPO / "model-tools" / "reference" / "vectors.json"
THRESHOLDS_PATH = REPO / "model-tools" / "parity" / "fp32-thresholds-v2.json"

COSINE_FLOOR = 0.9  # hard sanity floor (task 2.2); formal thresholds = 2.4


# --------------------------------------------------------------------------- #
# helpers                                                                     #
# --------------------------------------------------------------------------- #
def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cosine(a: torch.Tensor, b: torch.Tensor) -> float:
    a = a.flatten().double()
    b = b.flatten().double()
    return float(torch.dot(a, b) / (a.norm() * b.norm()))


def high_freq_max_abs_diff(a: torch.Tensor, b: torch.Tensor) -> float:
    """Max abs diff of the first-order difference (a simple high-frequency
    error indicator; no scipy dependency). Differences amplify HF content."""
    if a.numel() < 2:
        return 0.0
    da = a[1:] - a[:-1]
    db = b[1:] - b[:-1]
    return float((da - db).abs().max())


def load_export_model() -> KModelForONNX:
    """The model that gets exported: CustomSTFT (disable_complex=True) with
    eager BERT attention (the SDPA path converts a tensor to a Python bool
    during tracing and would not generalize)."""
    bundle = Path(__file__).resolve().parents[2] / "kokoro_sr_dragana_voice"
    kmodel = KModel(
        repo_id="hexgrad/Kokoro-82M",
        config=str(bundle / "config.json"),
        model=str(bundle / "kokoro_dragana_sr.pth"),
        disable_complex=True,
    ).eval()
    kmodel.bert.config._attn_implementation = "eager"
    return KModelForONNX(kmodel).eval()


# --------------------------------------------------------------------------- #
# export                                                                      #
# --------------------------------------------------------------------------- #
def export_onnx_file(model: KModelForONNX, example_ids: torch.Tensor,
                     example_ref_s: torch.Tensor) -> None:
    speed = torch.tensor(1.0, dtype=torch.float32)
    torch.onnx.export(
        model,
        (example_ids, example_ref_s, speed),
        f=str(ONNX_PATH),
        export_params=True,
        input_names=["input_ids", "ref_s", "speed"],
        output_names=["waveform", "pred_dur"],
        opset_version=OPSET_VERSION,
        dynamic_axes={
            "input_ids": {1: "seq_len"},
            "waveform": {0: "waveform_len"},
            "pred_dur": {0: "pred_dur_len"},
        },
        do_constant_folding=True,
        dynamo=False,
    )


# --------------------------------------------------------------------------- #
# graph boundary assertions                                                   #
# --------------------------------------------------------------------------- #
ONNX_DTYPE_BY_ELT = {
    onnx.TensorProto.FLOAT: "float32",
    onnx.TensorProto.INT64: "int64",
}


def _check_dim(got, want, kind, name):
    """Validate one graph dim against the spec: an int `want` means a static
    dim that must equal it; a string `want` means a dynamic (named) axis."""
    if isinstance(want, int):
        if not (isinstance(got, int) and got == want):
            raise AssertionError(
                f"{kind} {name}: expected static dim {want}, got {got!r}"
            )
        return False
    if not (isinstance(got, str) and got):
        raise AssertionError(
            f"{kind} {name}: expected a dynamic axis, got static/bare dim {got!r}"
        )
    return True


def assert_graph_boundary(path: Path) -> dict:
    model = onnx.load(str(path))
    info = model.opset_import
    ai_onnx_opset = next(i.version for i in info if i.domain in ("", "ai.onnx"))
    inputs = {i.name: i for i in model.graph.input}
    outputs = {o.name: o for o in model.graph.output}
    boundary = {"inputs": {}, "outputs": {}}
    expected_inputs = {
        "input_ids": ("int64", (1, "seq_len")),
        "ref_s": ("float32", (1, 256)),
        "speed": ("float32", ()),
    }
    expected_outputs = {
        "waveform": ("float32", ("waveform_len",)),
        "pred_dur": ("int64", ("pred_dur_len",)),
    }

    def record(kind, name, tensor, dtype, shape):
        t = tensor.type.tensor_type
        got_dtype = ONNX_DTYPE_BY_ELT.get(t.elem_type)
        if got_dtype != dtype:
            raise AssertionError(f"{kind} {name}: dtype {got_dtype} != {dtype}")
        dims = [
            d.dim_value if d.HasField("dim_value") else d.dim_param
            for d in t.shape.dim
        ]
        if len(dims) != len(shape):
            raise AssertionError(
                f"{kind} {name}: rank {len(dims)} != expected {len(shape)}"
            )
        dynamic = [_check_dim(got, want, kind, name) for got, want in zip(dims, shape)]
        boundary[kind + "s"][name] = {
            "dtype": got_dtype,
            "shape": [int(g) if isinstance(g, int) else g for g in dims],
            "dynamic": dynamic,
        }

    for name, (dtype, shape) in expected_inputs.items():
        record("input", name, inputs[name], dtype, shape)
    for name, (dtype, shape) in expected_outputs.items():
        record("output", name, outputs[name], dtype, shape)

    non_fp32 = []
    for init in model.graph.initializer:
        if init.data_type in (
            onnx.TensorProto.FLOAT16,
            onnx.TensorProto.DOUBLE,
            onnx.TensorProto.FLOAT8E4M3FN,
            onnx.TensorProto.FLOAT8E5M2,
        ):
            non_fp32.append((init.name, init.data_type))
    if non_fp32:
        raise AssertionError(f"non-FP32 float initializers: {non_fp32}")
    domains = sorted({n.domain for n in model.graph.node})
    return {
        "ir_version": model.ir_version,
        "ai_onnx_opset": ai_onnx_opset,
        "opset_imports": [
            {"domain": (i.domain or "ai.onnx"), "version": int(i.version)}
            for i in info
        ],
        "graph_domains": domains,
        "node_count": len(model.graph.node),
        "initializer_count": len(model.graph.initializer),
        "boundary": boundary,
    }


# --------------------------------------------------------------------------- #
# ORT + PyTorch runs, generality gate, drift                                  #
# --------------------------------------------------------------------------- #
def run_all_vectors(ort_sess, ref_wrapper: DraganaExportWrapper,
                    export_model: KModelForONNX, vectors: dict) -> list[dict]:
    seed = vectors["seed"]
    speed_np = torch.tensor(1.0, dtype=torch.float32).numpy()
    results = []
    for v in vectors["vectors"]:
        input_ids = torch.tensor([v["token_ids"]], dtype=torch.int64)
        ref_s = ref_wrapper.voice_table[v["voice_row_index"]].to(torch.float32)

        with torch.inference_mode():
            pt_wave, pt_dur = ref_wrapper.forward(
                input_ids, ref_s, 1.0, seed=seed
            )
            torch.manual_seed(seed)
            export_wave, export_dur = export_model(input_ids, ref_s, 1.0)

        t0 = time.perf_counter()
        onnx_wave, onnx_dur = ort_sess.run(
            None,
            {
                "input_ids": input_ids.numpy(),
                "ref_s": ref_s.numpy(),
                "speed": speed_np,
            },
        )
        ort_seconds = time.perf_counter() - t0

        onnx_wave = torch.from_numpy(onnx_wave)
        onnx_dur = torch.from_numpy(onnx_dur).long()
        n_pt = int(pt_wave.numel())
        n_export = int(export_wave.numel())
        n_onnx = int(onnx_wave.numel())

        results.append({
            "vector": v["id"],
            "sample_count_pytorch": n_pt,
            "sample_count_export_pytorch": n_export,
            "sample_count_onnx": n_onnx,
            "sample_count_match": n_pt == n_onnx,
            "sample_count_export_match": n_export == n_onnx,
            "pred_dur_match": bool(torch.equal(pt_dur, onnx_dur)),
            "max_abs_diff": float((pt_wave - onnx_wave).abs().max()) if n_pt == n_onnx else None,
            "mean_abs_diff": float((pt_wave - onnx_wave).abs().mean()) if n_pt == n_onnx else None,
            "cosine_similarity": cosine(pt_wave, onnx_wave) if n_pt == n_onnx else None,
            "high_freq_max_abs_diff": high_freq_max_abs_diff(pt_wave, onnx_wave) if n_pt == n_onnx else None,
            "export_max_abs_diff": float((export_wave - onnx_wave).abs().max()) if n_export == n_onnx else None,
            "export_mean_abs_diff": float((export_wave - onnx_wave).abs().mean()) if n_export == n_onnx else None,
            "export_cosine_similarity": cosine(export_wave, onnx_wave) if n_export == n_onnx else None,
            "export_high_freq_max_abs_diff": high_freq_max_abs_diff(export_wave, onnx_wave) if n_export == n_onnx else None,
            "pytorch_peak": float(pt_wave.abs().max()),
            "export_pytorch_peak": float(export_wave.abs().max()),
            "onnx_peak": float(onnx_wave.abs().max()),
            "pytorch_finite": bool(torch.isfinite(pt_wave).all()),
            "export_pytorch_finite": bool(torch.isfinite(export_wave).all()),
            "onnx_finite": bool(torch.isfinite(onnx_wave).all()),
            "ort_infer_seconds": ort_seconds,
        })
    return results


def check_length_generality(results: list[dict]) -> dict:
    """HARD GATE: the graph must be length-general. Require >= 3 distinct
    input lengths and exact sample-count match on EVERY vector."""
    distinct = sorted({r["sample_count_pytorch"] for r in results}, reverse=True)
    mismatches = [r["vector"] for r in results if not r["sample_count_match"]]
    if len(distinct) < 3:
        raise AssertionError(
            f"generality gate: need >= 3 distinct lengths, got {len(distinct)}: {distinct}"
        )
    if mismatches:
        detail = ", ".join(
            f"{r['vector']} (pt={r['sample_count_pytorch']} onnx={r['sample_count_onnx']})"
            for r in results if not r["sample_count_match"]
        )
        raise AssertionError(
            f"generality gate FAILED — output length is not data-dependent: {detail}"
        )
    return {
        "distinct_lengths": distinct,
        "num_vectors_checked": len(results),
        "all_sample_counts_match": True,
        "per_vector": [
            {
                "vector": r["vector"],
                "sample_count": r["sample_count_pytorch"],
                "onnx_sample_count": r["sample_count_onnx"],
                "match": r["sample_count_match"],
            }
            for r in results
        ],
    }


def compute_drift(results: list[dict]) -> dict:
    """Record exact-reference and export-path drift; do not enforce the floor."""
    return {
        "note": (
            "The exact-reference fields compare ORT (CustomSTFT, unseedable "
            "RNG) with PyTorch (exact TorchSTFT); the export-path fields compare "
            "ORT with PyTorch CustomSTFT. Exact-reference drift includes known "
            "CustomSTFT lossiness and vocoder RNG (design §11). The export-path "
            "comparison isolates ONNX conversion fidelity. Formal thresholds "
            "are task 2.4."
        ),
        "cosine_floor": COSINE_FLOOR,
        "sanity_baseline": "PyTorch KModelForONNX with disable_complex=True",
        "vectors": results,
    }


def assert_drift_floor(results: list[dict]) -> list[str]:
    """Enforce the hard sanity floor (task 2.2). Returns a list of violation
    strings (empty if the floor holds). The caller decides how to signal it."""
    violations = []
    for r in results:
        if not r["sample_count_match"]:
            violations.append(f"sample count mismatch on {r['vector']}")
        if not r["sample_count_export_match"]:
            violations.append(f"export-path sample count mismatch on {r['vector']}")
        if not (r["pytorch_finite"] and r["export_pytorch_finite"] and r["onnx_finite"]):
            violations.append(f"non-finite output on {r['vector']}")
        if (
            r["export_cosine_similarity"] is None
            or r["export_cosine_similarity"] <= COSINE_FLOOR
        ):
            violations.append(
                f"export-path cosine {r['export_cosine_similarity']} <= "
                f"{COSINE_FLOOR} on {r['vector']}"
            )
    return violations


def check_drift(results: list[dict]) -> dict:
    """Build drift evidence and fail if the task-2.2 sanity floor is missed."""
    drift = compute_drift(results)
    violations = assert_drift_floor(results)
    if violations:
        raise AssertionError("drift sanity gate FAILED: " + "; ".join(violations))
    return drift


def isolate_stft_lossiness(model_to_export: KModelForONNX,
                           ref_wrapper: DraganaExportWrapper,
                           vectors: dict) -> dict:
    """PyTorch-only isolation: run the CustomSTFT model (model_to_export) and
    the exact TorchSTFT model (ref_wrapper) on the greeting vector with the SAME
    seed. Both consume the identical vocoder RNG sequence, so the only difference
    is the STFT/iSTFT implementation. This confirms the drift is CustomSTFT
    lossiness, not ORT or RNG."""
    seed = vectors["seed"]
    v = next(x for x in vectors["vectors"] if x["id"] == "greeting-latin")
    input_ids = torch.tensor([v["token_ids"]], dtype=torch.int64)
    ref_s = ref_wrapper.voice_table[v["voice_row_index"]].to(torch.float32)

    torch.manual_seed(seed)
    with torch.inference_mode():
        w_custom, _ = model_to_export.kmodel.forward_with_tokens(input_ids, ref_s, 1.0)
    torch.manual_seed(seed)
    with torch.inference_mode():
        w_exact, _ = ref_wrapper.kmodel.forward_with_tokens(input_ids, ref_s, 1.0)

    return {
        "vector": "greeting-latin",
        "seed": seed,
        "description": (
            "CustomSTFT(PyTorch) vs TorchSTFT(PyTorch), same seed — isolates "
            "the STFT/iSTFT implementation difference (identical vocoder RNG)"
        ),
        "sample_count": int(w_custom.numel()),
        "cosine_similarity": cosine(w_custom, w_exact),
        "max_abs_diff": float((w_custom - w_exact).abs().max()),
        "mean_abs_diff": float((w_custom - w_exact).abs().mean()),
    }


# --------------------------------------------------------------------------- #
# manifest                                                                    #
# --------------------------------------------------------------------------- #
def write_manifest(boundary: dict, generality: dict, drift: dict, thresholds: dict) -> dict:
    manifest = {
        "kind": "onnx-interface-manifest",
        "task": "build-serbian-audiobook-mvp 2.2",
        "candidate": "dragana.onnx (FP32, not committed — regenerate and "
                     "verify against onnx_sha256)",
        "route": (
            "Option A (PM decision): legacy TorchScript exporter + "
            "disable_complex=True (CustomSTFT). Rationale: the dynamo "
            "exporter cannot carry the data-dependent sum(pred_dur) sequence "
            "length through the F0Ntrain LSTM; the official Kokoro export "
            "uses this route and it is length-general. See README."
        ),
        "onnx_file": "model-tools/export/dragana.onnx",
        "onnx_sha256": sha256_file(ONNX_PATH),
        "onnx_size_bytes": ONNX_PATH.stat().st_size,
        "exporter": EXPORTER,
        "torch_version": torch.__version__,
        "python": sys.version.split()[0],
        "onnx_version": onnx.__version__,
        "onnxruntime_version": onnxruntime.__version__,
        "onnxscript_version": onnxscript.__version__,
        "opset": {
            "ai_onnx": boundary["ai_onnx_opset"],
            "rationale": OPSET_RATIONALE,
        },
        "opset_imports": boundary["opset_imports"],
        "ir_version": boundary["ir_version"],
        "graph_domains": boundary["graph_domains"],
        "node_count": boundary["node_count"],
        "initializer_count": boundary["initializer_count"],
        "interface": {
            "inputs": {
                "input_ids": {
                    "dtype": "int64",
                    "shape": [1, "seq_len"],
                    "axis_names": {1: "seq_len"},
                    "bounds": {"seq_len": [2, MAX_INPUT_TOKENS]},
                    "note": "token IDs, 0 boundary tokens at both ends, values 0..177",
                },
                "ref_s": {
                    "dtype": "float32",
                    "shape": [1, 256],
                    "axis_names": {},
                    "note": "one already-selected Dragana style row (caller selects)",
                },
                "speed": {
                    "dtype": "float32",
                    "shape": [],
                    "axis_names": {},
                    "note": "positive scalar (0-dim); was a Python float in the pinned path",
                },
            },
            "outputs": {
                "waveform": {
                    "dtype": "float32",
                    "shape": ["waveform_len"],
                    "axis_names": {0: "waveform_len"},
                    "note": "mono 24 kHz; data-dependent length 600 * sum(pred_dur)",
                },
                "pred_dur": {
                    "dtype": "int64",
                    "shape": ["pred_dur_len"],
                    "axis_names": {0: "pred_dur_len"},
                    "note": "per-token predicted frame counts, >= 1 (== input_ids seq_len)",
                },
            },
            "observed_graph_boundary": boundary["boundary"],
        },
        "dynamic_axes": {
            "mechanism": "classic torch.onnx.export dynamic_axes (legacy exporter)",
            "input_ids": {"dim": 1, "name": "seq_len", "min": 2, "max": MAX_INPUT_TOKENS},
            "pred_dur": {"dim": 0, "name": "pred_dur_len",
                         "note": "data-dependent; equals input_ids seq_len at runtime"},
            "waveform": {"dim": 0, "name": "waveform_len",
                         "note": "data-dependent (600 * sum(pred_dur)); no static bound"},
        },
        "model_sha256": EXPECTED_MODEL_SHA256,
        "voice_sha256": EXPECTED_VOICE_SHA256,
        "voice_bundle": "kokoro_sr_dragana_voice/ (epoch-005)",
        "sample_rate": SAMPLE_RATE,
        "max_input_tokens": MAX_INPUT_TOKENS,
        "parity_thresholds": {
            "path": "model-tools/parity/fp32-thresholds-v2.json",
            "version": thresholds["thresholds_version"],
            "declared_before_candidate_evaluation": thresholds["declared_before_candidate_evaluation"],
        },
        "vocab_size": 178,
        "length_generality": generality,
        "drift": drift,
        "substitutions": [
            "NONE of the dynamo-path substitutions are used on this route: the "
            "legacy TorchScript exporter traces the pinned KModelForONNX "
            "(disable_complex=True) directly. BERT attention is forced to the "
            "manual 'eager' implementation (numerically identical to SDPA, "
            "~1e-5; the SDPA path converts a tensor to a Python bool during "
            "tracing and would not generalize to other input lengths).",
        ],
        "stft": {
            "exported": "CustomSTFT (disable_complex=True) — conv1d/conv_transpose1d "
                        "real-arithmetic approximation; DC/Nyquist doubling skipped",
            "reference": "TorchSTFT (disable_complex=False) — exact torch.stft/istft",
            "note": "The exported graph's vocoder STFT is a KNOWN lossy "
                    "approximation of the exact reference. The measured drift "
                    "is in the 'drift' section. Parity is statistical (design §11).",
        },
        "randomness": {
            "ops": ["RandomUniform (SineGen initial phase, istftnet.py:150)",
                    "RandomNormalLike (SineGen unvoiced noise, istftnet.py:205)"],
            "note": "ORT cannot seed these; parity is statistical (design §11). "
                    "The seed is not a graph input.",
        },
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main() -> int:
    t_start = time.perf_counter()
    print(f"[2.2] torch {torch.__version__}, threads={torch.get_num_threads()}")

    # The formal gate must exist and be valid before any candidate vectors run.
    thresholds = load_thresholds(THRESHOLDS_PATH)
    print(f"[2.4] loaded thresholds {thresholds['thresholds_version']}")

    ref_wrapper = DraganaExportWrapper()  # exact TorchSTFT reference (~22 s)
    model_to_export = load_export_model()  # CustomSTFT, eager attention (~22 s)

    vectors = json.loads(VECTORS_PATH.read_text())
    greeting = next(v for v in vectors["vectors"] if v["id"] == "greeting-latin")
    example_ids = torch.tensor([greeting["token_ids"]], dtype=torch.int64)
    example_ref_s = ref_wrapper.voice_table[greeting["voice_row_index"]].to(torch.float32)

    print("[2.2] exporting ONNX (legacy TorchScript, opset 18, CustomSTFT)...")
    t0 = time.perf_counter()
    export_onnx_file(model_to_export, example_ids, example_ref_s)
    export_seconds = time.perf_counter() - t0
    print(f"[2.2] exported {ONNX_PATH.stat().st_size / 1e6:.1f} MB in {export_seconds:.1f} s")

    onnx.checker.check_model(str(ONNX_PATH))
    print("[2.2] onnx.checker: OK")

    print("[2.2] asserting graph boundary dtypes/shapes...")
    boundary = assert_graph_boundary(ONNX_PATH)
    print(json.dumps(boundary["boundary"], indent=2))
    if any(d not in ("", "ai.onnx") for d in boundary["graph_domains"]):
        raise AssertionError(f"non-standard op domains present: {boundary['graph_domains']}")

    sess = onnxruntime.InferenceSession(str(ONNX_PATH), providers=["CPUExecutionProvider"])
    results = run_all_vectors(sess, ref_wrapper, model_to_export, vectors)

    print("[2.2] LENGTH-GENERALITY HARD GATE...")
    generality = check_length_generality(results)
    print(f"  distinct lengths: {generality['distinct_lengths']}")
    for p in generality["per_vector"]:
        print(f"    {p['vector']}: pt={p['sample_count']} onnx={p['onnx_sample_count']} match={p['match']}")

    print("[2.2] 7-VECTOR DRIFT (exact reference + export-path sanity, seed 20260826)...")
    drift = check_drift(results)
    for r in drift["vectors"]:
        print(
            f"    {r['vector']}: n={r['sample_count_onnx']} "
            f"ref_cos={r['cosine_similarity']:.6f} "
            f"export_cos={r['export_cosine_similarity']:.6f} "
            f"export_maxabs={r['export_max_abs_diff']:.5f} "
            f"export_hf={r['export_high_freq_max_abs_diff']:.5f} "
            f"dur={'Y' if r['pred_dur_match'] else 'N'}"
        )

    manifest = write_manifest(boundary, generality, drift, thresholds)
    print(f"[2.2] manifest -> {MANIFEST_PATH}")
    print(f"[2.2] onnx sha256: {manifest['onnx_sha256']}")
    print(f"[2.2] total {time.perf_counter() - t_start:.1f} s")
    print("ok: true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
