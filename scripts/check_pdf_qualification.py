#!/usr/bin/env python3
"""Verify the binary PDF qualification gate and production graph isolation."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "pdf-qualification/qualification-report.json"
FORBIDDEN = ("androidx.pdf", "pdfbox-android", "com.tom_roush:pdfbox-android")


def main() -> None:
    report = json.loads(REPORT.read_text())
    assert report["qualification"] == "no-pass"
    assert report["selected_candidate"] is None
    assert report["production_pdf_enabled"] is False
    assert set(report["matrix"]) == {"androidx-pdf", "pdfbox-android", "platform-pdf-renderer"}
    for candidate in report["matrix"].values():
        assert set(candidate) == {"30", "35", "36"}
    for path in (ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts", ROOT / "document-pdf/build.gradle.kts"):
        text = path.read_text().lower()
        assert not any(token in text for token in FORBIDDEN), path
    print("pdf qualification gate: PASS (verified no-pass / production disabled)")


if __name__ == "__main__":
    main()
