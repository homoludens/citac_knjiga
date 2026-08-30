#!/usr/bin/env python3
"""Fail-closed provenance and source-closure checks for Android inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_ROOT = Path(__file__).resolve().parents[1]
POLICY_RELATIVE = Path("model-tools/native/source-closure-v1.json")
TOOLCHAIN_RELATIVE = Path("gradle/toolchain.lock.json")
DATA_MANIFEST_RELATIVE = Path("model-tools/native/espeak-data-manifest-v1.json")
NATIVE_SUFFIXES = {
    ".aar", ".a", ".apk", ".class", ".dll", ".dylib", ".jar", ".o", ".obj", ".so"
}
MODEL_SUFFIXES = {
    ".bin", ".ckpt", ".onnx", ".ort", ".pt", ".pth", ".safetensors", ".tflite", ".zip"
}
FORBIDDEN_FILE_DEPENDENCIES = ("files(", "fileTree(", "flatDir", "jniLibs")


class ClosureError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ClosureError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(root: Path, relative: str | Path) -> dict[str, object]:
    path = root / relative
    require(path.is_file(), f"missing provenance file: {relative}")
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"provenance file is not an object: {relative}")
    return value


def verify_provenance(root: Path, policy: dict[str, object]) -> None:
    lock = read_json(root, TOOLCHAIN_RELATIVE)
    native = policy["native"]
    require(isinstance(native, dict), "native provenance is missing")
    native_source = native["commit"]
    require(native_source == lock["artifacts"]["espeak_ng_commit"], "native source commit differs from toolchain lock")
    android_build = native["android_build"]
    require(android_build["cmake"] == lock["android"]["cmake"], "native CMake version differs from toolchain lock")
    require(android_build["ndk"] == lock["android"]["ndk"], "native NDK version differs from toolchain lock")

    data_manifest = read_json(root, DATA_MANIFEST_RELATIVE)
    source = data_manifest["source"]
    require(source["commit"] == native_source, "data manifest source commit differs from closure policy")
    require(source["tag"] == native["tag"], "data manifest source tag differs from closure policy")
    require(data_manifest["native_build"]["abi"] == android_build["abi"], "data manifest ABI differs from closure policy")
    require(data_manifest["native_build"]["android_platform"] == android_build["platform"], "data manifest API differs from closure policy")
    require(data_manifest["native_build"]["build_type"] == android_build["build_type"], "data manifest build type differs from closure policy")
    require(data_manifest["native_build"]["cmake"] == android_build["cmake"], "data manifest CMake differs from closure policy")
    require(data_manifest["native_build"]["ndk"] == android_build["ndk"], "data manifest NDK differs from closure policy")
    require(data_manifest["native_build"]["local_patches"] == native["local_patches"], "native local-patch record differs from closure policy")

    cmake = (root / native["cmake_project"]).read_text(encoding="utf-8")
    tag = native["tag"]
    require(native["repository"] in cmake, "CMake does not declare the documented native repository")
    require(f"GIT_TAG {native_source}" in cmake, "CMake source revision is not pinned")
    require(f"VERSION {tag}" in cmake, "CMake project version differs from provenance")
    require((root / native["jni_source"]).is_file(), "JNI source is missing")

    runtime = policy["runtime_dependencies"]
    require(isinstance(runtime, list) and len(runtime) == 1, "runtime dependency inventory is not exact")
    dependency = runtime[0]
    coordinate = dependency["coordinate"]
    group, name, version = coordinate.split(":", 2)
    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    tts_gradle = (root / "tts-onnx/build.gradle.kts").read_text(encoding="utf-8")
    require(f'onnxruntime = "{version}"' in catalog, "ONNX Runtime version is not in the version catalog")
    require(f'module = "{group}:{name}"' in catalog, "ONNX Runtime module is not in the version catalog")
    require("debugImplementation(libs.onnxruntime.android)" in tts_gradle, "debug ONNX Runtime dependency is undeclared")
    require("releaseImplementation(libs.onnxruntime.android)" in tts_gradle, "release ONNX Runtime dependency is undeclared")
    verification = ET.parse(root / "gradle/verification-metadata.xml").getroot()
    namespace = "{https://schema.gradle.org/dependency-verification}"
    component = verification.find(f".//{namespace}component[@group='{group}'][@name='{name}'][@version='{version}']")
    require(component is not None, "ONNX Runtime component is absent from verification metadata")
    artifact = component.find(f"{namespace}artifact[@name='{name}-{version}.aar']")
    require(artifact is not None, "ONNX Runtime AAR is absent from verification metadata")
    checksum = artifact.find(f"{namespace}sha256")
    require(checksum is not None and checksum.attrib.get("value") == dependency["aar_sha256"],
            "ONNX Runtime AAR checksum differs from provenance")

    app_gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    require('create("fdroid")' in app_gradle, "F-Droid flavor is not declared")
    require(not re.search(r"fdroid(?:Implementation|Api|RuntimeOnly|CompileOnly|Compile)\s*[<(]", app_gradle),
            "F-Droid has an undeclared flavor-specific dependency")
    for path in sorted(root.glob("**/*.gradle.kts")):
        if any(part in {"build", ".gradle", ".kotlin"} for part in path.relative_to(root).parts):
            continue
        contents = path.read_text(encoding="utf-8")
        for marker in FORBIDDEN_FILE_DEPENDENCIES:
            require(marker not in contents, f"file-based or local native dependency in {path.relative_to(root)}: {marker}")


def verify_data_closure(root: Path, policy: dict[str, object]) -> int:
    data = policy["data"]
    manifest = read_json(root, data["manifest"])
    asset_root = root / data["asset_root"]
    require(asset_root.is_dir(), f"missing Android eSpeak data root: {data['asset_root']}")
    entries = manifest["files"]
    expected = {entry["path"] for entry in entries}
    actual = {
        path.relative_to(asset_root).as_posix()
        for path in asset_root.rglob("*")
        if path.is_file() and not path.is_symlink()
    }
    require(actual == expected, f"eSpeak data closure differs: expected {sorted(expected)}, found {sorted(actual)}")
    for entry in entries:
        path = asset_root / entry["path"]
        require(path.stat().st_size == entry["size_bytes"], f"eSpeak data size mismatch: {entry['path']}")
        require(sha256(path) == entry["sha256"], f"eSpeak data checksum mismatch: {entry['path']}")
    return len(entries)


def verify_source_files(root: Path, policy: dict[str, object]) -> tuple[int, int]:
    closure = policy["closure"]
    generated = set(closure["generated_directory_names"])
    allowlisted = {entry["path"]: entry["sha256"] for entry in closure["checked_in_binary_allowlist"]}
    findings: list[str] = []
    scanned = 0
    for relative_root in closure["scan_roots"]:
        source_root = root / relative_root
        require(source_root.is_dir(), f"source-closure root is missing: {relative_root}")
        for path in source_root.rglob("*"):
            if not path.is_file() or path.is_symlink():
                continue
            relative = path.relative_to(root).as_posix()
            if any(part in generated for part in path.relative_to(root).parts):
                continue
            scanned += 1
            suffix = path.suffix.lower()
            if suffix not in NATIVE_SUFFIXES and suffix not in MODEL_SUFFIXES:
                continue
            if relative in allowlisted:
                require(sha256(path) == allowlisted[relative], f"allowlisted binary checksum changed: {relative}")
                continue
            findings.append(relative)
    require(not findings, "unexpected native/prebuilt or model artifact(s): " + ", ".join(sorted(findings)))
    return scanned, len(allowlisted)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        policy = read_json(root, POLICY_RELATIVE)
        require(policy["schema"] == "citac-knjiga-native-source-closure", "unsupported source-closure schema")
        verify_provenance(root, policy)
        data_count = verify_data_closure(root, policy)
        scanned, allowlisted = verify_source_files(root, policy)
    except (ClosureError, OSError, ET.ParseError, KeyError, TypeError, ValueError) as error:
        print(f"source closure failed: {error}", file=sys.stderr)
        return 2
    print(f"source closure verified: {data_count} eSpeak data files, {scanned} source files, {allowlisted} documented binary exception")
    print("F-Droid inputs: no local native/model artifacts; ONNX Runtime AAR is explicitly locked from Maven Central")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
