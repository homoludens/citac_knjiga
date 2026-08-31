#!/usr/bin/env python3
"""Fetch only the pinned Serbian VITS revision into an external workspace."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

MODEL_ID = "daremc86/sr-cv-vits"
REVISION = "83dc1e1b95d85b9f5602dc94909706fc83dfbc6c"


def _run(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def fetch(destination: Path, revision: str = REVISION) -> dict[str, object]:
    if revision != REVISION:
        raise ValueError("only the immutable Serbian VITS revision is accepted")
    if destination.exists():
        raise ValueError("destination must be disposable and must not already exist")
    destination.parent.mkdir(parents=True, exist_ok=True)
    _run("git", "clone", "--no-checkout", "https://huggingface.co/daremc86/sr-cv-vits", str(destination))
    _run("git", "-C", str(destination), "fetch", "--depth=1", "origin", REVISION)
    _run("git", "-C", str(destination), "checkout", "--detach", REVISION)
    resolved = _run("git", "-C", str(destination), "rev-parse", "HEAD")
    if resolved != REVISION:
        raise ValueError("fetched source resolved to a different commit")
    files = []
    for path in sorted(destination.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"source contains a symlink: {path.relative_to(destination)}")
        if path.is_file() and ".git" not in path.relative_to(destination).parts:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            files.append({"path": path.relative_to(destination).as_posix(), "size_bytes": path.stat().st_size, "sha256": digest})
    return {"schema": "serbian-vits-source-manifest:1", "model_id": MODEL_ID, "requested_revision": REVISION, "resolved_commit": resolved, "workspace_policy": "disposable-desktop-outside-repository", "files": files}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = fetch(args.destination)
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    print(json.dumps({"ok": True, "resolved_commit": REVISION, "file_count": len(manifest["files"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
