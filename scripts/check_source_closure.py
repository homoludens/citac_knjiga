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
from urllib.parse import urlparse


DEFAULT_ROOT = Path(__file__).resolve().parents[1]
POLICY_RELATIVE = Path("model-tools/native/source-closure-v1.json")
TOOLCHAIN_RELATIVE = Path("gradle/toolchain.lock.json")
DATA_MANIFEST_RELATIVE = Path("model-tools/native/espeak-data-manifest-v1.json")
DESCRIPTOR_RELATIVE = Path("app/src/main/java/com/homoludens/citacknjiga/diagnostics/ModelDownloadConfig.kt")
ANDROID_NS = "http://schemas.android.com/apk/res/android"
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


def descriptor_records(source: str) -> set[tuple[str, str, str, int, str]]:
    records: set[tuple[str, str, str, int, str]] = set()
    pattern = re.compile(
        r"public val (KOKORO|VITS): ModelReleaseDescriptor = ModelReleaseDescriptor\((.*?)\n        \)",
        re.DOTALL,
    )
    for engine, body in pattern.findall(source):
        def field(name: str) -> str:
            match = re.search(rf"\b{name} = \"([^\"]+)\"", body)
            require(match is not None, f"model descriptor field is missing: {engine}.{name}")
            return match.group(1)

        url = re.search(r'\bassetUrl = "([^"]*)"\s*\+\s*"([^"]*)"', body)
        require(url is not None, f"model descriptor URL is missing: {engine}")
        size = re.search(r"\bexpectedSizeBytes = ([0-9_]+)L", body)
        require(size is not None, f"model descriptor size is missing: {engine}")
        records.add((engine, url.group(1) + url.group(2), field("assetFileName"), int(size.group(1).replace("_", "")), field("outerSha256")))
    return records


def verify_network_policy(root: Path, policy: dict[str, object]) -> None:
    network = policy.get("network_policy")
    require(isinstance(network, dict), "network policy is missing from source closure")
    require(network.get("schema") == "citac-knjiga-model-download-network-policy", "unsupported network policy schema")
    require(network.get("version") == 1, "unsupported network policy version")
    require(network.get("permission") == "android.permission.INTERNET", "network policy permission is not INTERNET")
    require(network.get("supporting_permissions") == ["android.permission.ACCESS_NETWORK_STATE"],
            "network policy supporting permissions are not constrained to WorkManager connectivity")
    require(network.get("cleartext") is False, "network policy permits cleartext traffic")
    require(network.get("allowed_host") == "github.com", "network policy host is not GitHub")
    prefix = network.get("allowed_path_prefix")
    require(isinstance(prefix, str) and prefix.startswith("/"), "network policy path prefix is invalid")

    assets = network.get("allowed_assets")
    require(isinstance(assets, list) and len(assets) == 2, "network policy must contain exactly two model assets")
    expected: set[tuple[str, str, str, int, str]] = set()
    for asset in assets:
        require(isinstance(asset, dict), "network policy asset is not an object")
        values = (asset.get("engine"), asset.get("url"), asset.get("filename"), asset.get("size_bytes"), asset.get("sha256"))
        require(all(isinstance(value, (str, int)) for value in values), "network policy asset metadata is incomplete")
        engine, url, filename, size, digest = values
        parsed = urlparse(url)
        require(parsed.scheme == "https" and parsed.hostname == network["allowed_host"] and not parsed.query and not parsed.fragment,
                f"network policy asset URL is not a pinned HTTPS GitHub URL: {url}")
        require(parsed.path.startswith(prefix) and parsed.path.endswith("/" + filename),
                f"network policy asset path is outside the approved release prefix: {url}")
        require(isinstance(size, int) and size > 0 and isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest),
                f"network policy asset integrity metadata is invalid: {filename}")
        expected.add((engine, url, filename, size, digest))
    require(len(expected) == len(assets), "network policy contains duplicate assets")
    descriptors = descriptor_records((root / DESCRIPTOR_RELATIVE).read_text(encoding="utf-8"))
    require(descriptors == expected, "network policy differs from pinned model descriptors")

    offline_operations = network.get("offline_operations")
    require(offline_operations == ["document_import", "generation", "runtime_dependency_acquisition"],
            "network policy does not preserve offline operation boundaries")
    forbidden = network.get("forbidden_network_api_terms")
    require(isinstance(forbidden, list) and forbidden, "network policy has no forbidden network API list")
    allowed_network_sources = network.get("allowed_network_source_files")
    require(isinstance(allowed_network_sources, list) and allowed_network_sources,
            "network policy has no model-download source allowlist")
    for relative in allowed_network_sources:
        path = root / relative
        require(isinstance(relative, str) and path.is_file() and relative in {
            candidate.relative_to(root).as_posix()
            for scan_root in policy["closure"]["scan_roots"]
            for candidate in (root / scan_root).rglob("*.kt")
        }, f"network source allowlist entry is invalid: {relative}")
    allowed_network_sources = set(allowed_network_sources)
    for relative_root in policy["closure"]["scan_roots"]:
        source_root = root / relative_root
        for path in source_root.rglob("*"):
            if not path.is_file() or path.is_symlink() or any(part in {"build", ".cxx", ".gradle", ".kotlin"} for part in path.relative_to(root).parts):
                continue
            if path.suffix.lower() not in {".java", ".kt"}:
                continue
            if path.relative_to(root).as_posix() in allowed_network_sources:
                continue
            contents = path.read_text(encoding="utf-8")
            for term in forbidden:
                require(term.lower() not in contents.lower(), f"network API is outside the model-download boundary: {path.relative_to(root)}: {term}")

    manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
    permissions = {
        node.attrib.get("{" + ANDROID_NS + "}name")
        for node in manifest.iter()
        if node.tag.rsplit("}", 1)[-1].startswith("uses-permission")
    }
    require(network["permission"] in permissions, "application manifest does not declare the model-download permission")
    require(set(network["supporting_permissions"]).issubset(permissions),
            "application manifest does not declare the WorkManager connectivity permission")
    application = manifest.find("application")
    require(application is not None and application.attrib.get("{" + ANDROID_NS + "}usesCleartextTraffic") == "false",
            "application manifest does not enforce HTTPS-only model downloads")


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
        verify_network_policy(root, policy)
        data_count = verify_data_closure(root, policy)
        scanned, allowlisted = verify_source_files(root, policy)
    except (ClosureError, OSError, ET.ParseError, KeyError, TypeError, ValueError) as error:
        print(f"source closure failed: {error}", file=sys.stderr)
        return 2
    print(f"source closure verified: {data_count} eSpeak data files, {scanned} source files, {allowlisted} documented binary exception")
    print("F-Droid inputs: no local native/model artifacts; model network policy is pinned to two GitHub assets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
