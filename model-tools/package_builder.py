"""Build and verify reproducible v1 Serbian model packages."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
from typing import Any, Iterable
from zipfile import ZIP_STORED, BadZipFile, ZipFile, ZipInfo

from package_manifest import load_and_validate, validate_manifest
from scripts.validate_preprocessing import (
    GoldenPreprocessingError,
    validate_golden_preprocessing,
)


class PackageError(ValueError):
    """Raised when a model package cannot be safely built or verified."""


_CHUNK_SIZE = 1 << 20
_FIXED_ZIP_DATE = (1980, 1, 1, 0, 0, 0)
_MAX_MANIFEST_BYTES = 16 << 20


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(_CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_manifest_bytes(manifest: dict[str, Any]) -> bytes:
    """Return stable UTF-8 bytes for the manifest stored in the archive."""
    return json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _declared_artifacts(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {artifact["path"]: artifact for artifact in manifest["artifacts"]}


def _safe_package_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or not path.parts
        or any(part in {"", ".", ".."} for part in path.parts)
        or "\\" in value
    ):
        raise PackageError(f"unsafe package path: {value!r}")
    return path


def _assert_legal_for_packaging(manifest: dict[str, Any]) -> None:
    legal = manifest["legal"]
    if legal["status"] != "cleared" or legal["model_distribution"] != "allowed":
        raise PackageError(
            "legal gate blocks package: model distribution is not cleared and allowed"
        )
    if legal["blocked_artifact_ids"]:
        raise PackageError(
            "legal gate blocks package: blocked_artifact_ids is not empty"
        )
    if legal["outstanding_reviews"]:
        raise PackageError("legal gate blocks package: outstanding reviews remain")

    pending_licenses = {
        entry["id"]
        for entry in manifest["licenses"]
        if entry["terms_status"] != "declared"
    }
    if pending_licenses:
        raise PackageError(
            "legal gate blocks package: pending license terms "
            + ", ".join(sorted(pending_licenses))
        )

    not_allowed = [
        artifact["artifact_id"]
        for artifact in manifest["artifacts"]
        if artifact["distribution_status"] != "allowed"
    ]
    if not_allowed:
        raise PackageError(
            "legal gate blocks package: artifacts are not allowed: "
            + ", ".join(sorted(not_allowed))
        )


def _iter_source_files(root: Path) -> Iterable[tuple[str, Path]]:
    for candidate in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if candidate.is_symlink():
            raise PackageError(f"payload symlinks are not allowed: {candidate}")
        if not candidate.is_file():
            continue
        yield candidate.relative_to(root).as_posix(), candidate


def _verify_payload_root(
    payload_root: Path, manifest: dict[str, Any]
) -> dict[str, Path]:
    if not payload_root.is_dir():
        raise PackageError(f"payload root is not a directory: {payload_root}")

    declared = _declared_artifacts(manifest)
    actual = dict(_iter_source_files(payload_root))
    undeclared = sorted(set(actual) - set(declared))
    if undeclared:
        raise PackageError("undeclared payload files: " + ", ".join(undeclared))

    missing = sorted(set(declared) - set(actual))
    if missing:
        raise PackageError("missing declared payload files: " + ", ".join(missing))

    for package_path, artifact in declared.items():
        _safe_package_path(package_path)
        source = actual[package_path]
        size = source.stat().st_size
        if size != artifact["size_bytes"]:
            raise PackageError(
                f"{artifact['artifact_id']}: size mismatch: "
                f"expected {artifact['size_bytes']}, got {size}"
            )
        actual_sha256 = sha256_file(source)
        if actual_sha256 != artifact["sha256"]:
            raise PackageError(
                f"{artifact['artifact_id']}: checksum mismatch: "
                f"expected {artifact['sha256']}, got {actual_sha256}"
            )
    return actual


def _zip_info(name: str) -> ZipInfo:
    info = ZipInfo(filename=name, date_time=_FIXED_ZIP_DATE)
    info.compress_type = ZIP_STORED
    info.create_system = 3
    info.create_version = 20
    info.extract_version = 20
    info.external_attr = 0o100644 << 16
    return info


def _write_archive(
    archive_path: Path,
    manifest: dict[str, Any],
    payloads: dict[str, Path],
) -> None:
    manifest_path = manifest["manifest"]["manifest_path"]
    _safe_package_path(manifest_path)
    if manifest_path in payloads:
        raise PackageError("manifest path must not be a payload path")

    with ZipFile(archive_path, "w", compression=ZIP_STORED, allowZip64=True) as archive:
        archive.writestr(_zip_info(manifest_path), canonical_manifest_bytes(manifest))
        for package_path in sorted(payloads):
            info = _zip_info(package_path)
            with payloads[package_path].open("rb") as source, archive.open(info, "w") as target:
                shutil.copyfileobj(source, target, length=_CHUNK_SIZE)


def _hash_archive_member(archive: ZipFile, info: ZipInfo) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with archive.open(info, "r") as stream:
        for chunk in iter(lambda: stream.read(_CHUNK_SIZE), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def validate_package(package_path: Path) -> dict[str, Any]:
    """Validate the complete archive, including its declared payload bytes."""
    if not package_path.is_file():
        raise PackageError(f"package does not exist: {package_path}")

    try:
        archive = ZipFile(package_path, "r")
    except (OSError, BadZipFile) as exc:
        raise PackageError(f"cannot open package: {package_path}") from exc

    with archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise PackageError("package contains duplicate file names")
        if any(info.is_dir() or info.filename.endswith("/") for info in infos):
            raise PackageError("package must not contain directory entries")

        # The manifest path is declared inside the manifest, so identify it by
        # its v1 top-level shape before checking the declared file set.
        manifest_candidates: list[tuple[str, bytes, dict[str, Any]]] = []
        for info in infos:
            if info.file_size > _MAX_MANIFEST_BYTES:
                continue
            try:
                candidate_bytes = archive.read(info)
                candidate = json.loads(candidate_bytes.decode("utf-8"))
            except (BadZipFile, UnicodeDecodeError, json.JSONDecodeError):
                continue
            if (
                isinstance(candidate, dict)
                and {"schema", "manifest", "artifacts"}.issubset(candidate)
            ):
                manifest_candidates.append((info.filename, candidate_bytes, candidate))
        if len(manifest_candidates) != 1:
            raise PackageError("package must contain exactly one v1 manifest")
        manifest_name, manifest_bytes, manifest = manifest_candidates[0]
        try:
            validate_manifest(manifest)
        except ValueError as exc:
            raise PackageError(f"package manifest validation failed: {exc}") from exc
        if manifest_bytes != canonical_manifest_bytes(manifest):
            raise PackageError("package manifest is not canonically serialized")
        _assert_legal_for_packaging(manifest)

        declared = _declared_artifacts(manifest)
        declared_manifest_path = manifest["manifest"]["manifest_path"]
        if declared_manifest_path != manifest_name:
            raise PackageError("package manifest_path does not match its archive path")
        expected_names = set(declared) | {declared_manifest_path}
        if set(names) != expected_names:
            undeclared = sorted(set(names) - expected_names)
            missing = sorted(expected_names - set(names))
            details = []
            if undeclared:
                details.append("undeclared=" + ",".join(undeclared))
            if missing:
                details.append("missing=" + ",".join(missing))
            raise PackageError("package file set mismatch: " + "; ".join(details))

        info_by_name = {info.filename: info for info in infos}
        for package_path, artifact in declared.items():
            _safe_package_path(package_path)
            info = info_by_name[package_path]
            try:
                actual_sha256, size = _hash_archive_member(archive, info)
            except (BadZipFile, OSError) as exc:
                raise PackageError(
                    f"cannot read packaged artifact {package_path}"
                ) from exc
            if size != artifact["size_bytes"]:
                raise PackageError(
                    f"{artifact['artifact_id']}: packaged size mismatch: "
                    f"expected {artifact['size_bytes']}, got {size}"
                )
            if actual_sha256 != artifact["sha256"]:
                raise PackageError(
                    f"{artifact['artifact_id']}: packaged checksum mismatch: "
                    f"expected {artifact['sha256']}, got {actual_sha256}"
                )
        return manifest


def build_package(
    manifest_path: Path,
    payload_root: Path,
    output_path: Path,
) -> Path:
    """Build a package and publish it only after complete archive validation."""
    try:
        manifest = load_and_validate(manifest_path)
    except (OSError, ValueError) as exc:
        raise PackageError(f"manifest validation failed: {exc}") from exc
    _assert_legal_for_packaging(manifest)
    try:
        validate_golden_preprocessing()
    except (GoldenPreprocessingError, OSError, ValueError) as exc:
        raise PackageError(f"golden preprocessing gate blocks package: {exc}") from exc
    payloads = _verify_payload_root(payload_root, manifest)

    if not output_path.parent.is_dir():
        raise PackageError(f"output directory does not exist: {output_path.parent}")

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=output_path.parent,
            prefix=f".{output_path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
        _write_archive(temporary_path, manifest, payloads)
        validate_package(temporary_path)
        os.replace(temporary_path, output_path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return output_path
