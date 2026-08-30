#!/usr/bin/env python3
"""Build, sign, and verify the two application release APKs.

Model packages are deliberately not an input to this command. The signed
output directory is an app-only bundle containing APKs, checksums, and a
redacted provenance manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
LOCK = ROOT / "gradle/toolchain.lock.json"
FDROID_CONFIG = ROOT / "fdroid/check-config-v1.json"
APK_SUFFIXES = {
    ".bin", ".ckpt", ".flac", ".m4a", ".mp3", ".onnx", ".ort", ".pt",
    ".pth", ".safetensors", ".tflite", ".wav", ".zip",
}
ALLOWED_GENERATED = {"DebugProbesKt.bin"}
SECRET_PATTERNS = (
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9_]{20,}\b"),
    re.compile(rb"(?i)\b(?:api[_-]?key|client[_-]?secret|password)\s*[:=]\s*[\"'][^\"']{8,}"),
)
MODEL_ENTRY = re.compile(r"(?i)(?:^|/)(?:model|models|voice|voices|kokoro|dragana)(?:/|$)")


class ReleaseError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(command: list[str], *, env: dict[str, str] | None = None) -> str:
    print("$ " + " ".join(command))
    try:
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, check=True)
    except FileNotFoundError as error:
        raise ReleaseError(f"required command is missing: {command[0]}") from error
    except subprocess.CalledProcessError as error:
        details = (error.stdout + error.stderr).strip()
        raise ReleaseError(f"command failed ({error.returncode}): {' '.join(command)}\n{details}") from error
    if result.stdout:
        print(result.stdout.rstrip())
    return result.stdout + result.stderr


def sdk_tool(name: str) -> Path:
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk_value, "ANDROID_HOME or ANDROID_SDK_ROOT is required")
    sdk = Path(sdk_value)
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    tool = sdk / "build-tools" / lock["android"]["build_tools"] / name
    require(tool.is_file(), f"Android build tool is missing: {tool}")
    return tool


def parse_badging(text: str) -> dict[str, str]:
    match = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        text,
    )
    require(match is not None, "aapt2 did not report package metadata")
    return {"application_id": match.group(1), "version_code": match.group(2), "version_name": match.group(3)}


def scan_apk_payload(apk: Path) -> None:
    require(zipfile.is_zipfile(apk), f"not a readable APK ZIP: {apk.name}")
    try:
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                normalized = PurePosixPath(name)
                require(not normalized.is_absolute() and ".." not in normalized.parts, f"unsafe APK entry: {name}")
                suffix = normalized.suffix.lower()
                if suffix in APK_SUFFIXES and normalized.name not in ALLOWED_GENERATED:
                    raise ReleaseError(f"APK contains forbidden model/audio/generated payload: {name}")
                if MODEL_ENTRY.search(name) and name.startswith("assets/"):
                    raise ReleaseError(f"APK contains a model-like asset entry: {name}")
                data = archive.read(name)
                for pattern in SECRET_PATTERNS:
                    require(not pattern.search(data), f"possible secret in APK entry: {name}")
    except zipfile.BadZipFile as error:
        raise ReleaseError(f"not a readable APK ZIP: {apk.name}") from error


def verify_signature(apk: Path, apksigner: Path) -> dict[str, object]:
    output = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
    schemes = {
        scheme: value == "true"
        for scheme, value in re.findall(r"Verified using (v\d) scheme[^:]*:\s*(true|false)", output)
    }
    require(schemes.get("v2") or schemes.get("v3"), "APK has no verified v2 or v3 signature")
    digest_match = re.search(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", output)
    require(digest_match is not None, "apksigner did not report a certificate SHA-256 digest")
    dn_match = re.search(r"certificate DN:\s*(.+)", output)
    return {
        "status": "signed",
        "schemes": schemes,
        "certificate_sha256": digest_match.group(1).replace(":", "").lower(),
        "certificate_dn": dn_match.group(1).strip() if dn_match else "unknown",
    }


def verify_apk(
    apk: Path,
    expected: dict[str, str],
    *,
    require_signed: bool,
    aapt2: Path,
    apksigner: Path | None,
) -> dict[str, object]:
    scan_apk_payload(apk)
    metadata = parse_badging(run([str(aapt2), "dump", "badging", str(apk)]))
    for key, value in expected.items():
        require(metadata[key] == value, f"{apk.name} {key} is {metadata[key]!r}, expected {value!r}")
    if require_signed:
        require(apksigner is not None, "apksigner is required for signed verification")
        signing = verify_signature(apk, apksigner)
    else:
        signing = {"status": "unsigned", "schemes": {}}
    return {
        "name": apk.name,
        "size": apk.stat().st_size,
        "sha256": sha256(apk),
        "package": metadata,
        "signing": signing,
    }


def expected_packages() -> dict[str, dict[str, str]]:
    config = json.loads(FDROID_CONFIG.read_text(encoding="utf-8"))
    fdroid = config["flavor"]
    return {
        "standard": {
            "application_id": "com.homoludens.citacknjiga",
            "version_code": "1",
            "version_name": "0.1.0",
        },
        "fdroid": {
            "application_id": fdroid["application_id"],
            "version_code": str(fdroid["version_code"]),
            "version_name": fdroid["version_name"],
        },
    }


def source_provenance(require_clean: bool) -> dict[str, object]:
    commit = run(["git", "rev-parse", "HEAD"]).strip()
    status = run(["git", "status", "--porcelain", "--untracked-files=all"]).strip()
    require(not require_clean or not status, "source tree is not clean; release builds require a clean checkout")
    lock_inputs = [
        LOCK,
        ROOT / "gradle/wrapper/gradle-wrapper.properties",
        ROOT / "gradle/libs.versions.toml",
        ROOT / "gradle/verification-metadata.xml",
        ROOT / "settings-gradle.lockfile",
        APP / "gradle.lockfile",
    ]
    return {
        "git_commit": commit,
        "git_tree_clean": not bool(status),
        "toolchain_lock_sha256": sha256(LOCK),
        "locked_input_sha256": {path.relative_to(ROOT).as_posix(): sha256(path) for path in lock_inputs},
    }


def locate_built_apk(flavor: str) -> Path:
    candidates = sorted((APP / "build/outputs/apk" / flavor / "release").glob("*.apk"))
    require(len(candidates) == 1, f"expected one {flavor} release APK, found {len(candidates)}")
    return candidates[0]


def signing_config(args: argparse.Namespace) -> tuple[Path, dict[str, str]]:
    path_value = args.keystore or os.environ.get("ANDROID_KEYSTORE_PATH")
    keystore = Path(path_value).expanduser().resolve() if path_value else None
    require(keystore is not None and keystore.is_file(), "a real external keystore is required; refusing fake production signing")
    require(ROOT not in keystore.parents, "keystore must be outside the repository")
    values = {
        "ANDROID_KEY_ALIAS": os.environ.get("ANDROID_KEY_ALIAS", ""),
        "ANDROID_KEYSTORE_PASSWORD": os.environ.get("ANDROID_KEYSTORE_PASSWORD", ""),
        "ANDROID_KEY_PASSWORD": os.environ.get("ANDROID_KEY_PASSWORD", ""),
    }
    require(all(values.values()), "ANDROID_KEY_ALIAS, ANDROID_KEYSTORE_PASSWORD, and ANDROID_KEY_PASSWORD are required")
    return keystore, values


def write_checksums(output: Path, artifacts: list[dict[str, object]]) -> None:
    (output / "SHA256SUMS").write_text(
        "".join(f"{artifact['sha256']}  {artifact['name']}\n" for artifact in artifacts),
        encoding="utf-8",
    )


def validate_artifact_directory(output: Path, *, allow_unsigned: bool) -> None:
    require(output.is_dir(), f"artifact directory is missing: {output}")
    allowed = {"SHA256SUMS", "release-manifest.json"}
    files = {path.name for path in output.iterdir() if path.is_file()}
    apk_files = {name for name in files if name.endswith(".apk")}
    suffix = "-unsigned" if allow_unsigned else ""
    require(apk_files == {
        f"citac-knjiga-standard-v0.1.0{suffix}.apk",
        f"citac-knjiga-fdroid-v0.1.0-fdroid{suffix}.apk",
    },
            "artifact directory must contain exactly the two versioned app APKs")
    require(files == allowed | apk_files, "artifact directory contains non-release payloads or untracked files")
    for path in output.rglob("*"):
        if path.is_file() and path.name not in allowed | apk_files:
            raise ReleaseError(f"unexpected release output: {path.name}")


def read_checksums(output: Path) -> dict[str, str]:
    checksums: dict[str, str] = {}
    for line in (output / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+\.apk)", line)
        require(match is not None, f"invalid checksum line: {line}")
        require(match.group(2) not in checksums, f"duplicate checksum entry: {match.group(2)}")
        checksums[match.group(2)] = match.group(1)
    return checksums


def verify_checksums(output: Path) -> None:
    for name, expected in read_checksums(output).items():
        require(expected == sha256(output / name), f"checksum mismatch: {name}")


def verify_output(output: Path, *, allow_unsigned: bool) -> dict[str, object]:
    validate_artifact_directory(output, allow_unsigned=allow_unsigned)
    manifest = json.loads((output / "release-manifest.json").read_text(encoding="utf-8"))
    require(manifest.get("schema") == "citac-knjiga-release-artifacts", "unsupported release manifest schema")
    require(manifest.get("version") == 1, "unsupported release manifest version")
    require(manifest.get("build", {}).get("unsigned_local_build") is allow_unsigned,
            "unsigned status does not match the verification mode")
    separation = manifest.get("separation", {})
    require(separation.get("model_packages_included") is False, "model packages must be separate from app artifacts")
    require(separation.get("audio_artifacts_included") is False, "generated audio must be separate from app artifacts")
    require(separation.get("secrets_included") is False, "secrets must be absent from app artifacts")
    checksums = read_checksums(output)
    verify_checksums(output)
    require(len(manifest["artifacts"]) == 2, "release manifest must describe exactly two app artifacts")
    require(set(checksums) == {item["name"] for item in manifest["artifacts"]}, "manifest/checksum artifact sets differ")
    aapt2 = sdk_tool("aapt2")
    apksigner = sdk_tool("apksigner")
    verified: list[dict[str, object]] = []
    for flavor, expected in expected_packages().items():
        unsigned = "-unsigned" if allow_unsigned else ""
        name = f"citac-knjiga-{flavor}-v{expected['version_name']}{unsigned}.apk"
        apk = output / name
        require(checksums.get(name) == sha256(apk), f"checksum mismatch: {name}")
        verified.append(verify_apk(apk, expected, require_signed=not allow_unsigned, aapt2=aapt2, apksigner=apksigner))
    by_name = {item["name"]: item for item in manifest["artifacts"]}
    for item in verified:
        recorded = by_name[item["name"]]
        require(recorded["sha256"] == item["sha256"], f"manifest checksum mismatch: {item['name']}")
        require(recorded["package"] == item["package"], f"manifest version metadata mismatch: {item['name']}")
        require(recorded["signing"] == item["signing"], f"manifest signing identity mismatch: {item['name']}")
    return manifest


def build(args: argparse.Namespace) -> None:
    output = Path(args.output_dir).expanduser().resolve()
    require(output != ROOT and ROOT not in output.parents, "release output must be outside the repository")
    output.mkdir(parents=True, exist_ok=True)
    require(not any(output.iterdir()), "release output directory must be empty")
    source = source_provenance(args.require_clean)
    if args.unsigned:
        signing = None
    else:
        keystore, passwords = signing_config(args)
    gradle = ROOT / "gradlew"
    require(gradle.is_file(), "Gradle wrapper is missing")
    run([str(gradle), ":app:assembleStandardRelease", ":app:assembleFdroidRelease", "--no-daemon", "--max-workers=2", "--console=plain", "--dependency-verification=strict"])
    aapt2 = sdk_tool("aapt2")
    apksigner = sdk_tool("apksigner")
    records: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix="citac-release-", dir=output.parent) as temporary:
        for flavor, expected in expected_packages().items():
            built = locate_built_apk(flavor)
            if args.unsigned:
                final = output / f"citac-knjiga-{flavor}-v{expected['version_name']}-unsigned.apk"
                shutil.copyfile(built, final)
                record = verify_apk(final, expected, require_signed=False, aapt2=aapt2, apksigner=None)
            else:
                aligned = Path(temporary) / f"{flavor}.aligned.apk"
                unsigned = Path(temporary) / f"{flavor}.unsigned.apk"
                shutil.copyfile(built, unsigned)
                run([str(sdk_tool("zipalign")), "-p", "-f", "4", str(unsigned), str(aligned)])
                final = output / f"citac-knjiga-{flavor}-v{expected['version_name']}.apk"
                sign_env = os.environ.copy()
                sign_env.update(passwords)
                run([
                    str(apksigner), "sign", "--min-sdk-version", "30",
                    "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                    "--ks", str(keystore), "--ks-key-alias", passwords["ANDROID_KEY_ALIAS"],
                    "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                    "--out", str(final), str(aligned),
                ], env=sign_env)
                record = verify_apk(final, expected, require_signed=True, aapt2=aapt2, apksigner=apksigner)
            records.append(record)
    write_checksums(output, records)
    manifest = {
        "schema": "citac-knjiga-release-artifacts",
        "version": 1,
        "source": source,
        "build": {
            "gradle_tasks": [":app:assembleStandardRelease", ":app:assembleFdroidRelease"],
            "dependency_verification": "strict",
            "unsigned_local_build": bool(args.unsigned),
        },
        "artifacts": records,
        "separation": {
            "model_packages_included": False,
            "audio_artifacts_included": False,
            "secrets_included": False,
            "optional_model_packages": "separate user-imported packages; not a release input or output",
        },
    }
    (output / "release-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.unsigned:
        print(f"unsigned local artifacts written to {output}; these are not release artifacts")
    else:
        verify_output(output, allow_unsigned=False)
        print(f"signed release artifacts verified: {output}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    build_parser = subparsers.add_parser("build")
    build_parser.add_argument("--output-dir", default="/tmp/citac-knjiga-release-artifacts")
    build_parser.add_argument("--keystore", help="external keystore path; never place it in the repository")
    build_parser.add_argument("--require-clean", action="store_true")
    build_parser.add_argument("--unsigned", action="store_true", help="debug-only local output with -unsigned names")
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--artifact-dir", required=True)
    verify_parser.add_argument("--allow-unsigned", action="store_true", help="only for explicitly labeled local builds")
    args = parser.parse_args()
    try:
        if args.command == "build":
            build(args)
        else:
            manifest = verify_output(Path(args.artifact_dir).expanduser().resolve(), allow_unsigned=args.allow_unsigned)
            print(f"release artifact manifest verified: {manifest['source']['git_commit']}")
        return 0
    except (ReleaseError, OSError, json.JSONDecodeError) as error:
        print(f"release artifact check failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
