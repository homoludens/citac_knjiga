#!/usr/bin/env python3
"""Run deterministic, scanner-like checks for the F-Droid application variant."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "fdroid/check-config-v1.json"
GENERATED_DIRS = {"build", ".cxx", ".gradle", ".kotlin", "__pycache__"}
TEXT_SUFFIXES = {".gradle", ".gradle.kts", ".java", ".json", ".kt", ".kts", ".lockfile", ".properties", ".xml"}
DEPENDENCY_SUFFIXES = {".gradle", ".gradle.kts", ".lockfile", ".toml"}
ANDROID_NS = "http://schemas.android.com/apk/res/android"
SECRET_PATTERNS = (
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9_]{20,}\b"),
    re.compile(rb"(?i)\b(?:api[_-]?key|client[_-]?secret|password)\s*[:=]\s*[\"'][^\"']{8,}"),
)
TRACKER_BYTES = re.compile(
    rb"(?i)(?:com[./]google[./](?:firebase|android[./]gms)|io[./]sentry|"
    rb"com[./]sentry|com[./](?:appsflyer|mixpanel|amplitude|onesignal)|"
    rb"firebase[-./]crashlytics)"
)


class CheckError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckError(message)


def load_config() -> dict[str, object]:
    value = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    require(value.get("schema") == "citac-knjiga-fdroid-check", "unsupported F-Droid check configuration")
    require(value.get("version") == 1, "unsupported F-Droid check configuration version")
    return value


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def iter_source_files(config: dict[str, object]):
    for root_name in config["source"]["roots"]:
        source_root = ROOT / root_name
        require(source_root.is_dir(), f"missing F-Droid source root: {root_name}")
        for path in source_root.rglob("*"):
            if not path.is_file() or path.is_symlink():
                continue
            if any(part in GENERATED_DIRS for part in path.relative_to(ROOT).parts):
                continue
            yield path


def run_check(command: list[str]) -> None:
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    if result.stdout:
        print(result.stdout.rstrip())
    if result.returncode != 0:
        if result.stderr:
            print(result.stderr.rstrip(), file=sys.stderr)
        raise CheckError(f"check failed: {' '.join(command)}")


def check_flavor(config: dict[str, object]) -> None:
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    flavor = config["flavor"]
    require('create("fdroid")' in gradle, "app does not declare the fdroid flavor")
    require('dimension = "distribution"' in gradle, "fdroid flavor is not in the distribution dimension")
    require('applicationIdSuffix = ".fdroid"' in gradle, "fdroid application ID suffix changed")
    require('versionNameSuffix = "-fdroid"' in gradle, "fdroid version suffix changed")
    require('buildConfigField("String", "DISTRIBUTION", "\\"fdroid\\"")' in gradle,
            "fdroid distribution metadata is missing")
    require(not re.search(r"fdroid(?:Implementation|Api|RuntimeOnly|CompileOnly|Compile)\s*[<(]", gradle),
            "fdroid has a flavor-specific dependency")
    require(f"versionCode = {flavor['version_code']}" in gradle, "version code is not locked by F-Droid policy")
    require(f"versionName = \"{str(flavor['version_name']).removesuffix('-fdroid')}\"" in gradle,
            "version name is not locked by F-Droid policy")


def check_source(config: dict[str, object]) -> None:
    terms = [term.encode() for term in config["source"]["forbidden_dependency_terms"]]
    allowed = set(config["source"]["allowed_source_artifacts"])
    allowed_network = set(config["source"]["allowed_network_source_files"])
    suffixes = set(config["source"]["forbidden_artifact_suffixes"])
    findings: list[str] = []
    for path in iter_source_files(config):
        path_name = relative(path)
        if path.suffix.lower() in suffixes and path_name not in allowed:
            findings.append(f"embedded artifact {path_name}")
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        data = path.read_bytes()
        lowered = data.lower()
        if path.suffix.lower() in DEPENDENCY_SUFFIXES:
            for term in terms:
                if term.lower() in lowered:
                    findings.append(f"forbidden dependency term {term.decode()} in {path_name}")
        if path.suffix.lower() in {".java", ".kt"}:
            if path_name not in allowed_network:
                for term in config["source"]["forbidden_network_api_terms"]:
                    if term.encode().lower() in lowered:
                        findings.append(f"forbidden network API term {term} in {path_name}")
        for pattern in SECRET_PATTERNS:
            if pattern.search(data):
                findings.append(f"possible secret in {path_name}")
    require(not findings, "; ".join(sorted(set(findings))))


def check_notices(config: dict[str, object]) -> None:
    notice_json = ROOT / "app/src/main/assets/notices/dependency-license-inventory.json"
    notice_md = ROOT / "app/src/main/assets/notices/THIRD_PARTY_NOTICES.md"
    require(notice_json.is_file() and notice_json.stat().st_size > 0, "dependency notice JSON is missing")
    require(notice_md.is_file() and notice_md.stat().st_size > 0, "third-party notice Markdown is missing")
    inventory = json.loads(notice_json.read_text(encoding="utf-8"))
    require(inventory.get("schema") == "citac-knjiga-license-inventory", "invalid dependency notice schema")
    require("fdroid" in inventory.get("constraints", {}).get("release_flavors", []),
            "dependency notices do not cover fdroid")
    require(not inventory.get("findings", {}).get("proprietary_services"), "proprietary service finding is present")
    require(not inventory.get("findings", {}).get("network_runtime_artifacts"), "network runtime finding is present")
    require("F-Droid" in notice_md.read_text(encoding="utf-8"), "notices do not identify the F-Droid bundle")


def merged_manifests() -> list[Path]:
    return sorted((ROOT / "app/build/intermediates/merged_manifest").glob("**/AndroidManifest.xml"))


def check_manifest(path: Path, config: dict[str, object]) -> None:
    document = ET.parse(path)
    permissions = {
        node.attrib.get(f"{{{ANDROID_NS}}}name")
        for node in document.getroot().iter()
        if node.tag.rsplit("}", 1)[-1].startswith("uses-permission")
    }
    required = set(config["manifest"]["required_permissions"])
    require(required.issubset(permissions), f"{relative(path)} is missing required permission(s): {sorted(required - permissions)}")
    forbidden = permissions.intersection(config["manifest"]["forbidden_permissions"])
    require(not forbidden, f"{relative(path)} declares prohibited network permission(s): {sorted(forbidden)}")
    application = document.getroot().find("application")
    require(application is not None, f"{relative(path)} has no application element")
    cleartext = application.attrib.get(f"{{{ANDROID_NS}}}usesCleartextTraffic")
    require(cleartext == str(config["manifest"]["uses_cleartext_traffic"]).lower(),
            f"{relative(path)} cleartext policy is not enforced")


def apk_path() -> Path | None:
    paths = sorted((ROOT / "app/build/outputs/apk").glob("fdroid/release/*.apk"))
    return paths[0] if len(paths) == 1 else None


def check_apk(path: Path, config: dict[str, object]) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        allowed_markers = [marker.encode() for marker in config["apk"]["allowed_framework_markers"]]
        required = set(config["apk"]["required_assets"])
        require(required.issubset(names), "F-Droid APK is missing bundled notices")
        payload_suffixes = set(config["apk"]["forbidden_payload_suffixes"])
        allowed_generated = set(config["apk"]["allowed_generated_payload_entries"])
        payloads = [
            name for name in names
            if Path(name).suffix.lower() in payload_suffixes and Path(name).name not in allowed_generated
        ]
        require(not payloads, "F-Droid APK contains model/audio payload(s): " + ", ".join(sorted(payloads)))
        allowed_native = set(config["apk"]["allowed_native_libraries"])
        native = [Path(name).name for name in names if name.startswith("lib/") and name.endswith(".so")]
        require(set(native).issubset(allowed_native), "undeclared APK native library: " + ", ".join(sorted(set(native) - allowed_native)))
        for name in names:
            data = archive.read(name)
            for pattern in SECRET_PATTERNS:
                require(not pattern.search(data), f"possible secret in F-Droid APK entry: {name}")
            for marker in allowed_markers:
                data = data.replace(marker, b"")
            require(not TRACKER_BYTES.search(data), f"tracker/proprietary marker in F-Droid APK entry: {name}")

    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    aapt2 = sdk / "build-tools/35.0.0/aapt2"
    require(aapt2.is_file(), "aapt2 is missing; cannot verify APK version/build metadata")
    result = subprocess.run([str(aapt2), "dump", "badging", str(path)], capture_output=True, text=True, check=True)
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", result.stdout)
    require(match is not None, "aapt2 did not report F-Droid package metadata")
    flavor = config["flavor"]
    require(match.group(1) == flavor["application_id"], "F-Droid APK application ID differs from policy")
    require(match.group(2) == str(flavor["version_code"]), "F-Droid APK version code differs from policy")
    require(match.group(3) == flavor["version_name"], "F-Droid APK version name differs from policy")


def report_external_tools(config: dict[str, object]) -> None:
    found = [name for name in config["external_scanners"] if shutil.which(name)]
    if found:
        print("external scanner commands available: " + ", ".join(found))
        print("external scanner invocation is not claimed; no F-Droid metadata repository is configured")
    else:
        print("external fdroid/scanner tooling unavailable: " + ", ".join(config["external_scanners"]))
        print("substitute checks ran; this is not a real F-Droid scan")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-build", action="store_true", help="require and inspect the assembled F-Droid release APK")
    args = parser.parse_args()
    try:
        config = load_config()
        check_flavor(config)
        check_source(config)
        check_notices(config)
        run_check([sys.executable, "scripts/verify_toolchain.py", "--scope", "static"])
        run_check([sys.executable, "scripts/check_source_closure.py"])
        manifests = [path for path in merged_manifests() if "fdroid" in path.as_posix().lower()]
        if args.require_build:
            require(manifests, "no merged F-Droid manifest was produced")
            require(apk_path() is not None, "no F-Droid release APK was produced")
        for path in manifests:
            check_manifest(path, config)
        if apk := apk_path():
            check_apk(apk, config)
        report_external_tools(config)
    except (CheckError, OSError, ET.ParseError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"F-Droid checks failed: {error}", file=sys.stderr)
        return 2
    print("F-Droid checks passed: source, policy, notices, permissions, payload, metadata, and closure")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
