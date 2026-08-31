"""Small, dependency-free primitives used by the Serbian VITS qualification.

The module deliberately has no model loader and no network client. Raw source,
checkpoints, and generated audio are inputs supplied from outside the repo.
"""
from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence

MODEL_ID = "daremc86/sr-cv-vits"
REVISION = "83dc1e1b95d85b9f5602dc94909706fc83dfbc6c"
SPEAKER_LABEL = "Dragana"
SPEAKER_ID = 0
NATIVE_RATE_HZ = 22_050
FINAL_RATE_HZ = 24_000
PACKAGE_SCHEMA = "serbian-vits-model-package:1"
GATE_ORDER = (
    "identity",
    "legal",
    "conversion",
    "desktop_parity",
    "android_parity",
    "serbian_quality",
    "android_matrix",
)

_LATIN = str.maketrans({
    "A": "А", "B": "Б", "C": "Ц", "Č": "Ч", "Ć": "Ћ", "D": "Д",
    "Đ": "Ђ", "E": "Е", "F": "Ф", "G": "Г", "H": "Х", "I": "И",
    "J": "Ј", "K": "К", "L": "Л", "M": "М", "N": "Н", "O": "О",
    "P": "П", "R": "Р", "S": "С", "Š": "Ш", "T": "Т", "U": "У",
    "V": "В", "Z": "З", "Ž": "Ж", "a": "а", "b": "б", "c": "ц",
    "č": "ч", "ć": "ћ", "d": "д", "đ": "ђ", "e": "е", "f": "ф",
    "g": "г", "h": "х", "i": "и", "j": "ј", "k": "к", "l": "л",
    "m": "м", "n": "н", "o": "о", "p": "п", "r": "р", "s": "с",
    "š": "ш", "t": "т", "u": "у", "v": "в", "z": "з", "ž": "ж",
})
_DIGITS = {
    "0": "нула", "1": "један", "2": "два", "3": "три", "4": "четири",
    "5": "пет", "6": "шест", "7": "седам", "8": "осам", "9": "девет",
}
_ABBREVIATIONS = {
    "нпр.": "на пример", "тј.": "то јест", "итд.": "и тако даље",
    "др.": "доктор", "г.": "година", "стр.": "страна",
}
_PROTECTED = re.compile(r"(?:https?://\S+|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,})")
_UNSUPPORTED = re.compile(r"[^\w\s.,!?;:()\-—–'\"/№%+А-Яа-яЉљЊњЂђЈјЋћЏџŠšŽžČčĆćĐđ]", re.UNICODE)


def canonical_json(value: Any) -> bytes:
    import json

    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _fail(message: str) -> None:
    raise ValueError(message)


def validate_identity(manifest: Mapping[str, Any]) -> None:
    candidate = manifest.get("candidate")
    _fail("missing candidate identity") if not isinstance(candidate, Mapping) else None
    if candidate.get("model_id") != MODEL_ID:
        _fail("candidate model_id is not the pinned model")
    if candidate.get("revision") != REVISION or candidate.get("resolved_commit") != REVISION:
        _fail("candidate revision is not the pinned immutable commit")
    if candidate.get("speaker") != {"label": SPEAKER_LABEL, "id": SPEAKER_ID}:
        _fail("candidate speaker identity must be Dragana id 0")
    if candidate.get("native_rate_hz") != NATIVE_RATE_HZ:
        _fail("candidate native sample rate must be 22050 Hz")
    if candidate.get("final_rate_hz") != FINAL_RATE_HZ or candidate.get("channels") != 1:
        _fail("candidate downstream contract must be 24000 Hz mono")
    if tuple(manifest.get("gate_order", ())) != GATE_ORDER:
        _fail("qualification gate order is not canonical")


def _safe_relative(value: str) -> None:
    path = PurePosixPath(value)
    if path.is_absolute() or "\\" in value or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        _fail(f"unsafe source/package path: {value!r}")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_source_manifest(record: Mapping[str, Any], root: Path) -> None:
    if record.get("model_id") != MODEL_ID or record.get("requested_revision") != REVISION:
        _fail("source manifest is not for the pinned candidate")
    if record.get("resolved_commit") != REVISION:
        _fail("source manifest did not resolve the pinned commit")
    files = record.get("files")
    if not isinstance(files, list) or not files:
        _fail("source manifest has no files")
    root = root.resolve()
    seen: set[str] = set()
    for entry in files:
        relative = entry.get("path") if isinstance(entry, Mapping) else None
        if not isinstance(relative, str):
            _fail("source manifest file path is missing")
        _safe_relative(relative)
        if relative in seen:
            _fail(f"duplicate source path: {relative}")
        seen.add(relative)
        path = (root / relative).resolve()
        if root not in path.parents or path.is_symlink() or not path.is_file():
            _fail(f"source file is missing, linked, or outside workspace: {relative}")
        if entry.get("size_bytes") != path.stat().st_size or entry.get("sha256") != _sha256_file(path):
            _fail(f"source identity mismatch: {relative}")


def legal_status(record: Mapping[str, Any]) -> str:
    required = ("model_code", "training_data", "voice_permission", "conversion_inputs", "licenses", "attributions", "modification_notice")
    missing = [key for key in required if not record.get(key)]
    decision = record.get("distribution_decision")
    if missing:
        return "UNRESOLVED"
    if decision not in {"ALLOWED", "BLOCKED"}:
        return "UNRESOLVED"
    if record.get("contradictions"):
        return "BLOCKED"
    return decision


def run_gates(gates: Iterable[Mapping[str, Any]]) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    blocked = False
    for gate in gates:
        name = str(gate.get("id", ""))
        result = str(gate.get("result", "UNRESOLVED")).upper()
        if name not in GATE_ORDER:
            _fail(f"unknown qualification gate: {name}")
        if result not in {"PASS", "FAIL", "UNRESOLVED"}:
            _fail(f"invalid result for gate {name}: {result}")
        evidence = gate.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            result = "UNRESOLVED"
        if blocked and result == "PASS":
            result = "UNRESOLVED"
        if result != "PASS":
            blocked = True
        records.append({"id": name, "result": result, "evidence": evidence or [], "reason": gate.get("reason")})
    ids = [record["id"] for record in records]
    if ids != list(GATE_ORDER):
        _fail("qualification gates must be complete and ordered")
    return {"gates": records, "outcome": "ACCEPTED" if not blocked else "REJECTED"}


_FORBIDDEN_PACKAGE_SUFFIXES = {".bin", ".ckpt", ".onnx.data", ".pt", ".pth", ".py", ".pyc", ".sh", ".so", ".safetensors"}


def validate_package_entries(entries: Sequence[str], declared: Sequence[str]) -> None:
    declared_set = set(declared)
    if len(entries) != len(set(entries)):
        _fail("package contains duplicate entries")
    for name in entries:
        _safe_relative(name)
        suffix = name.lower()
        if any(suffix.endswith(forbidden) for forbidden in _FORBIDDEN_PACKAGE_SUFFIXES):
            _fail(f"unsafe package entry: {name}")
    undeclared = sorted(set(entries) - declared_set)
    missing = sorted(declared_set - set(entries))
    if undeclared or missing:
        _fail(f"package entry set mismatch: undeclared={undeclared}, missing={missing}")


def preprocess_text(text: str, *, latin_policy: str = "transliterate-case-aware-v1") -> dict[str, Any]:
    if not isinstance(text, str) or not text.strip():
        _fail("input text is empty")
    if latin_policy != "transliterate-case-aware-v1":
        _fail(f"unsupported Latin policy: {latin_policy}")
    value = unicodedata.normalize("NFC", text)
    value = "".join(" " if ch.isspace() else ch for ch in value if not unicodedata.category(ch).startswith("C"))
    value = re.sub(r"\s+", " ", value).strip()
    diagnostics: list[str] = []
    protected: dict[str, str] = {}

    def hold(match: re.Match[str]) -> str:
        key = f" protectedspan{len(protected)} "
        protected[key] = match.group(0)
        return key

    value = _PROTECTED.sub(hold, value)
    value = value.translate(_LATIN)
    for short, expansion in _ABBREVIATIONS.items():
        value = value.replace(short, expansion)
    if re.search(r"\d", value):
        value = re.sub(r"\d", lambda match: f" {_DIGITS[match.group(0)]} ", value)
    unsupported = sorted(set(_UNSUPPORTED.findall(value)))
    if unsupported:
        diagnostics.append("unsupported-input:" + ",".join(f"U+{ord(ch):04X}" for ch in unsupported))
    for key, original in protected.items():
        value = value.replace(key, original)
    value = re.sub(r"\s+", " ", value).strip()
    return {"text": value, "protected_spans": sorted(protected.values()), "diagnostics": diagnostics, "policy": "serbian-vits-preprocessing-v1"}


def chunk_boundaries(symbol_count: int, *, max_symbols: int = 510, operational_symbols: int = 507) -> list[dict[str, int]]:
    if symbol_count < 0 or max_symbols <= 0 or operational_symbols <= 0 or operational_symbols > max_symbols:
        _fail("invalid VITS chunk contract")
    if symbol_count <= max_symbols:
        return [{"start": 0, "end": symbol_count}]
    boundaries = []
    start = 0
    while symbol_count - start > max_symbols:
        end = start + operational_symbols
        boundaries.append({"start": start, "end": end})
        start = end
    boundaries.append({"start": start, "end": symbol_count})
    return boundaries


def _valid_audio(samples: Sequence[float]) -> None:
    if not samples:
        _fail("audio is empty")
    if any(not math.isfinite(value) for value in samples):
        _fail("audio contains a non-finite sample")
    if any(abs(value) >= 1.0 for value in samples):
        _fail("audio sample is clipped")


def resample_22050_to_24000(samples: Sequence[float]) -> list[float]:
    """Apply the one frozen linear-phase interpolation conversion exactly once."""
    _valid_audio(samples)
    output_length = round(len(samples) * FINAL_RATE_HZ / NATIVE_RATE_HZ)
    if output_length == 1:
        return [float(samples[0])]
    result: list[float] = []
    scale = (len(samples) - 1) / (output_length - 1)
    for index in range(output_length):
        position = index * scale
        left = min(int(position), len(samples) - 1)
        right = min(left + 1, len(samples) - 1)
        fraction = position - left
        result.append(float(samples[left] * (1.0 - fraction) + samples[right] * fraction))
    _valid_audio(result)
    return result


def acceptance_eligibility(summary: Mapping[str, Any]) -> bool:
    if summary.get("outcome") != "ACCEPTED":
        return False
    if summary.get("candidate") != {"model_id": MODEL_ID, "revision": REVISION}:
        return False
    gates = summary.get("gates")
    return isinstance(gates, list) and [gate.get("result") for gate in gates] == ["PASS"] * len(GATE_ORDER)


def engine_options(summary: Mapping[str, Any]) -> list[str]:
    """Return the conditional UI boundary without changing the Android app."""
    return ["kokoro", "vits"] if acceptance_eligibility(summary) else ["kokoro"]


def generation_identity(provenance: Mapping[str, Any]) -> str:
    required = ("engine", "model", "revision", "speaker_id", "preprocessing", "native_rate_hz", "final_rate_hz", "inference", "resampler", "audio_processing")
    if any(key not in provenance for key in required):
        _fail("generation provenance is incomplete")
    return sha256_bytes(canonical_json({key: provenance[key] for key in required}))


def validate_resampler_manifest(manifest: Mapping[str, Any]) -> None:
    required = ("identity", "native_rate_hz", "final_rate_hz", "ratio", "output_length", "boundary", "channels", "coefficient_table", "checksum")
    if any(not manifest.get(key) and manifest.get(key) != 0 for key in required):
        _fail("resampler manifest is incomplete")
    if manifest.get("native_rate_hz") != NATIVE_RATE_HZ or manifest.get("final_rate_hz") != FINAL_RATE_HZ or manifest.get("channels") != 1:
        _fail("resampler rate or channel contract is invalid")
    if manifest.get("checksum") != sha256_bytes(canonical_json(manifest.get("coefficient_table"))):
        _fail("resampler coefficient checksum is invalid")


def validate_quality_corpus(corpus: Mapping[str, Any]) -> None:
    required = {"cyrillic", "latin", "diacritics", "digraphs", "numbers", "abbreviations", "punctuation", "long-input", "chunking"}
    categories = {case.get("category") for case in corpus.get("cases", []) if isinstance(case, Mapping)}
    if corpus.get("speaker") != {"label": SPEAKER_LABEL, "id": SPEAKER_ID} or corpus.get("latin_policy") != "transliterate-case-aware-v1":
        _fail("quality corpus speaker or Latin policy is missing")
    if required - categories:
        _fail("quality corpus is missing: " + ", ".join(sorted(required - categories)))


def validate_android_matrix(matrix: Mapping[str, Any]) -> None:
    targets = matrix.get("targets")
    if matrix.get("abi") != "arm64-v8a" or not isinstance(targets, list) or {item.get("api") for item in targets} != {30, 35, 36}:
        _fail("Android matrix must contain API 30, 35, and 36 arm64-v8a targets")
    if any(item.get("status") != "UNAVAILABLE" for item in targets):
        _fail("matrix fixture must not substitute an unavailable target")
