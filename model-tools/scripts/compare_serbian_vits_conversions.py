#!/usr/bin/env python3
"""Compare two external conversion outputs byte-for-byte."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


def digest(path: Path) -> str:
    if not path.is_file():
        raise ValueError(f"conversion output is missing: {path}")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compare(first: Path, second: Path) -> bool:
    first_files = sorted(path.relative_to(first).as_posix() for path in first.rglob("*") if path.is_file())
    second_files = sorted(path.relative_to(second).as_posix() for path in second.rglob("*") if path.is_file())
    return first_files == second_files and all(digest(first / name) == digest(second / name) for name in first_files)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=Path)
    parser.add_argument("second", type=Path)
    args = parser.parse_args()
    try:
        equal = compare(args.first, args.second)
    except OSError as error:
        print(error)
        return 1
    print("byte-identical" if equal else "different")
    return 0 if equal else 2


if __name__ == "__main__":
    raise SystemExit(main())
