#!/usr/bin/env python3
"""Validate the redacted task-12.6 release documentation bundle."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from generate_release_docs import (
    BUNDLE,
    GENERATOR,
    INPUTS,
    NOTICE_JSON,
    NOTICE_MD,
    OUTPUTS,
    build_sbom,
    build_manifest,
    read_json,
    render_documents,
    rel,
    sha256,
)


ROOT = BUNDLE.parents[1]
FORBIDDEN = (
    re.compile(r"(?:^|[\s\"'(`])/(?:home|tmp|var|Users|data|sdcard)(?:[/\s\"'`) ]|$)"),
    re.compile(r"(?i)(?:content|file|android\.resource)://"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"(?i)\b(?:api[_-]?key|client[_-]?secret|password)\s*[:=]"),
)
PAYLOAD_SUFFIXES = {".onnx", ".pth", ".pt", ".wav", ".m4a", ".mp3", ".zip", ".a", ".so", ".aar"}


class ValidationError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def validate_text() -> None:
    for path in BUNDLE.iterdir():
        require(path.is_file(), f"bundle entry is not a file: {path.name}")
        require(path.suffix.lower() in {".md", ".json"}, f"unexpected bundle file type: {path.name}")
        data = path.read_text(encoding="utf-8")
        for pattern in FORBIDDEN:
            require(not pattern.search(data), f"redaction rule failed in {path.name}: {pattern.pattern}")


def validate_manifest(manifest: dict[str, object]) -> None:
    require(manifest == build_manifest(), "bundle manifest is stale or modified; regenerate bundle")
    require(manifest["schema"] == "citac-knjiga-release-document-bundle", "unsupported bundle schema")
    require(manifest["version"] == 1 and manifest["bundle_id"] == "task-12-6", "unsupported bundle identity")
    require(tuple(manifest["outputs"]) == OUTPUTS, "bundle output list differs from generator")
    require(set(path.name for path in BUNDLE.iterdir()) == set(OUTPUTS), "bundle contains stale or missing files")
    generator = manifest["generated_by"]
    require(generator["path"] == GENERATOR.as_posix(), "generator path differs")
    require(generator["sha256"] == sha256(ROOT / GENERATOR), "generator changed; regenerate bundle")
    expected_inputs = list(INPUTS) + [GENERATOR.as_posix()]
    actual_inputs = {item["path"]: item["sha256"] for item in manifest["inputs"]}
    require(set(actual_inputs) == set(expected_inputs), "bundle input list differs from generator")
    for path in expected_inputs:
        source = ROOT / path
        require(source.is_file(), f"missing bundle input: {path}")
        require(actual_inputs[rel(source)] == sha256(source), f"input drift: {path}")
    references = manifest["notice_references"]
    for key, path in (("inventory", NOTICE_JSON), ("markdown", NOTICE_MD)):
        require(references[key]["path"] == rel(path), f"notice path differs: {key}")
        require(references[key]["sha256"] == sha256(path), f"notice drift: {key}")


def validate_sbom(sbom: dict[str, object], inventory: dict[str, object]) -> None:
    require(sbom["bomFormat"] == "CycloneDX" and sbom["specVersion"] == "1.5", "unsupported SBOM format")
    expected = {f"pkg:maven/{row['group']}/{row['name']}@{row['version']}" for row in inventory["components"]}
    expected.add("pkg:generic/espeak-ng@1.52.0")
    actual = {component["bom-ref"] for component in sbom["components"]}
    require(actual == expected, "SBOM component set differs from audited inventory")
    require(len(actual) == len(sbom["components"]), "SBOM contains duplicate component references")
    require(all("UNKNOWN" not in str(component) for component in sbom["components"]), "SBOM contains unknown license")
    require(str(sbom["serialNumber"]).startswith("urn:uuid:"), "SBOM serial is missing")


def validate_rendered_outputs(inventory: dict[str, object]) -> None:
    closure = read_json(ROOT / "model-tools/native/source-closure-v1.json")
    package = read_json(ROOT / "model-tools/package/model-package-v1.example.json")
    preprocessing = read_json(ROOT / "model-tools/preprocessing/preprocessing-contract-v1.json")
    data_manifest = read_json(ROOT / "model-tools/native/espeak-data-manifest-v1.json")
    sbom = build_sbom(inventory, closure)
    expected_documents = render_documents(
        inventory,
        sbom,
        package["legal"],
        package,
        preprocessing,
        closure,
        data_manifest,
    )
    for name, content in expected_documents.items():
        require((BUNDLE / name).read_text(encoding="utf-8") == content, f"output drift: {name}")
    expected_sbom = json.dumps(sbom, indent=2, sort_keys=True) + "\n"
    require((BUNDLE / "sbom.cdx.json").read_text(encoding="utf-8") == expected_sbom, "output drift: sbom.cdx.json")


def main() -> int:
    try:
        require(BUNDLE.is_dir(), "release documentation bundle is missing")
        manifest = read_json(BUNDLE / "bundle-manifest.json")
        inventory = read_json(NOTICE_JSON)
        validate_manifest(manifest)
        validate_text()
        validate_rendered_outputs(inventory)
        validate_sbom(read_json(BUNDLE / "sbom.cdx.json"), inventory)
        print(f"release documentation bundle valid: {BUNDLE.relative_to(ROOT)}")
        return 0
    except (ValidationError, OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"release documentation validation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
