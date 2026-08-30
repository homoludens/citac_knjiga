#!/usr/bin/env python3
"""Fail-closed checks for the versions and integrity locks used by CI/builds."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "gradle/toolchain.lock.json"


class VerificationError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def command(*args: str) -> str:
    try:
        result = subprocess.run(args, check=True, capture_output=True, text=True)
    except FileNotFoundError:
        raise VerificationError(f"required command is missing: {args[0]}") from None
    except subprocess.CalledProcessError as error:
        output = (error.stdout + error.stderr).strip()
        raise VerificationError(f"command failed: {' '.join(args)}: {output}") from error
    return result.stdout + result.stderr


def package_version(lock_text: str, package: str) -> str:
    pattern = rf"(?ms)^\[\[package\]\]\s+name = \"{re.escape(package)}\"\s+version = \"([^\"]+)\""
    match = re.search(pattern, lock_text)
    require(match is not None, f"model-tools/uv.lock is missing package {package}")
    return match.group(1)


def verify_static(lock: dict[str, object]) -> None:
    wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    gradle = lock["gradle"]
    escaped_url = gradle["distribution_url"].replace(":", "\\:")
    require(f"distributionUrl={escaped_url}" in wrapper,
            "Gradle wrapper distribution URL differs from gradle/toolchain.lock.json")
    require(f"distributionSha256Sum={gradle['sha256']}" in wrapper,
            "Gradle wrapper checksum differs from gradle/toolchain.lock.json")

    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
    versions = catalog.get("versions", {})
    for name, expected in lock["android_dependencies"].items():
        require(versions.get(name) == expected,
                f"gradle/libs.versions.toml version {name!r} is {versions.get(name)!r}, expected {expected!r}")

    properties = (ROOT / "gradle.properties").read_text()
    require("org.gradle.dependency.verification=strict" in properties,
            "Gradle dependency verification is not strict")
    lockfiles = [
        ROOT / "settings-gradle.lockfile",
        *(ROOT / module / "gradle.lockfile"
          for module in ("app", "core", "tts-onnx", "document-epub", "playback-export")),
    ]
    for lockfile in lockfiles:
        require(lockfile.is_file(), f"missing dependency lock: {lockfile}")
        text = lockfile.read_text()
        require("latest" not in text and "+" not in text and "SNAPSHOT" not in text,
                f"dynamic dependency found in {lockfile}")

    model_project = tomllib.loads((ROOT / "model-tools/pyproject.toml").read_text())
    dependencies = model_project["project"]["dependencies"]
    expected_specs = {
        "onnx": f"onnx=={lock['model_tools']['onnx']}",
        "onnxruntime": f"onnxruntime=={lock['model_tools']['onnxruntime']}",
        "onnxscript": f"onnxscript=={lock['model_tools']['onnxscript']}",
        "soundfile": f"soundfile=={lock['model_tools']['soundfile']}",
    }
    for package, expected in expected_specs.items():
        require(expected in dependencies, f"model-tools does not exactly pin {package}")
    for package, expected in {
        "jsonschema": f"jsonschema=={lock['model_tools']['jsonschema']}",
        "pytest": f"pytest=={lock['model_tools']['pytest']}",
        "pyyaml": f"pyyaml=={lock['model_tools']['pyyaml']}",
    }.items():
        require(expected in model_project["dependency-groups"]["dev"],
                f"model-tools dev dependencies do not exactly pin {package}")
    require(model_project["project"]["requires-python"] == f"=={lock['python']['version']}",
            "model-tools Python requirement is not exact")

    python_lock = (ROOT / "model-tools/uv.lock").read_text()
    require(f'requires-python = "=={lock["python"]["version"]}"' in python_lock,
            "model-tools/uv.lock Python requirement differs from toolchain lock")
    for package, expected in lock["model_tools"].items():
        if package.endswith("_commit") or package == "kokoro_version":
            continue
        require(package_version(python_lock, package) == expected,
                f"model-tools/uv.lock version for {package} is not {expected}")
    require(package_version(python_lock, "kokoro") == lock["model_tools"]["kokoro_version"],
            "model-tools/uv.lock Kokoro version is not pinned")
    require(lock["model_tools"]["kokoro_commit"] in python_lock,
            "uv.lock does not contain the pinned Kokoro commit")
    require((ROOT / "model-tools/.python-version").read_text().strip() == lock["python"]["version"],
            "model-tools/.python-version differs from toolchain lock")

    native = json.loads((ROOT / "model-tools/native/espeak-data-manifest-v1.json").read_text())
    require(native["source"]["commit"] == lock["artifacts"]["espeak_ng_commit"],
            "eSpeak-NG source commit differs from toolchain lock")
    require(native["native_build"]["cmake"] == lock["android"]["cmake"],
            "eSpeak-NG CMake pin differs from toolchain lock")
    require(native["native_build"]["ndk"] == lock["android"]["ndk"],
            "eSpeak-NG NDK pin differs from toolchain lock")
    cmake = (ROOT / "tts-onnx/src/main/cpp/CMakeLists.txt").read_text()
    require(f"GIT_TAG {lock['artifacts']['espeak_ng_commit']}" in cmake,
            "CMake eSpeak-NG GIT_TAG differs from toolchain lock")
    require(f"VERSION {native['source']['tag']}" in cmake,
            "CMake eSpeak-NG version differs from native manifest")

    namespace = "{https://schema.gradle.org/dependency-verification}"
    verification = ET.parse(ROOT / "gradle/verification-metadata.xml").getroot()
    found_checksum = False
    for component in verification.findall(f".//{namespace}component"):
        if component.attrib == {
            "group": "com.microsoft.onnxruntime",
            "name": "onnxruntime-android",
            "version": lock["model_tools"]["onnxruntime"],
        }:
            for artifact in component.findall(f"{namespace}artifact"):
                if artifact.attrib.get("name") == "onnxruntime-android-1.29.0.aar":
                    checksum = artifact.find(f"{namespace}sha256")
                    found_checksum = checksum is not None and checksum.attrib.get("value") == lock["artifacts"]["onnxruntime_android_aar_sha256"]
    require(found_checksum, "ONNX Runtime Android AAR checksum is not verified")


def verify_model_tools(lock: dict[str, object]) -> None:
    expected = tuple(int(part) for part in lock["python"]["version"].split("."))
    require(sys.version_info[:3] == expected,
            f"Python {'.'.join(map(str, sys.version_info[:3]))} is active; require {lock['python']['version']}")
    require(lock["python"]["uv"] in command("uv", "--version"),
            "uv version differs from gradle/toolchain.lock.json")
    require(lock["python"]["espeak_ng"] in command("espeak-ng", "--version"),
            "eSpeak-NG version differs from gradle/toolchain.lock.json")


def verify_android(lock: dict[str, object]) -> None:
    java = command("java", "-version")
    require(lock["jdk"]["version"] in java and lock["jdk"]["build"] in java,
            "JDK version differs from gradle/toolchain.lock.json")
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    require(str(sdk) != "." and sdk.is_dir(), "ANDROID_HOME or ANDROID_SDK_ROOT is required")
    for relative in (
        f"platforms/android-{lock['android']['compile_sdk']}",
        f"build-tools/{lock['android']['build_tools']}",
        f"cmake/{lock['android']['cmake']}",
        f"ndk/{lock['android']['ndk']}",
    ):
        require((sdk / relative).is_dir(), f"required Android SDK package is missing: {relative}")
    platform = (sdk / f"platforms/android-{lock['android']['compile_sdk']}/source.properties").read_text()
    require(f"AndroidVersion.ApiLevel={lock['android']['compile_sdk']}" in platform,
            "Android platform API differs from toolchain lock")
    for relative, version in (
        (f"build-tools/{lock['android']['build_tools']}/source.properties", lock['android']['build_tools']),
        (f"cmake/{lock['android']['cmake']}/source.properties", lock['android']['cmake']),
        (f"ndk/{lock['android']['ndk']}/source.properties", lock['android']['ndk']),
    ):
        require(f"Pkg.Revision={version}" in (sdk / relative).read_text().replace(" ", ""),
                f"installed SDK revision differs for {relative}")
    adb = sdk / "platform-tools/adb"
    require(adb.is_file(), "platform-tools/adb is missing")
    require(f"Version {lock['android']['platform_tools']}" in command(str(adb), "version"),
            "platform-tools revision differs from toolchain lock")
    cmake = sdk / f"cmake/{lock['android']['cmake']}/bin/cmake"
    require(f"cmake version {lock['android']['cmake']}" in command(str(cmake), "--version"),
            "Android CMake version differs from toolchain lock")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scope", choices=("all", "static", "model", "android"), default="all")
    args = parser.parse_args()
    lock = json.loads(LOCK_PATH.read_text())
    try:
        verify_static(lock)
        if args.scope in ("all", "model"):
            verify_model_tools(lock)
        if args.scope in ("all", "android"):
            verify_android(lock)
    except VerificationError as error:
        print(f"toolchain verification failed: {error}", file=sys.stderr)
        return 2
    print(f"toolchain verified: {LOCK_PATH.relative_to(ROOT)} ({args.scope})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
