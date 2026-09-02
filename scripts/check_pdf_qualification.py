#!/usr/bin/env python3
"""Verify the binary PDF qualification gate and production graph isolation."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "pdf-qualification/qualification-report.json"
CONSUMER_BUILD = ROOT / "pdf-qualification/android-consumer/build.gradle.kts"
SOURCE_CLOSURE = ROOT / "pdf-qualification/android-consumer/qualification-closure.json"
PDFBOX = "com.tom-roush:pdfbox-android:2.0.27.0"


def main() -> None:
    report = json.loads(REPORT.read_text())
    closure = json.loads(SOURCE_CLOSURE.read_text())
    assert report["qualification"] == "pass"
    assert report["selected_candidate"] == PDFBOX
    assert report["production_pdf_enabled"] is True
    assert report["candidate"] == PDFBOX
    assert report["qualification_scope"] == {
        "production": {"api": "33", "abi": "arm64-v8a"},
        "development": {"api": "35", "abi": "x86_64"},
        "non_gating": ["30", "36"],
    }
    assert closure["candidate"]["coordinate"] == report["candidate"]
    for api in ("33", "35"):
        assert report["matrix"]["pdfbox-android"][api]["status"] == "passed"
    for api in ("30", "36"):
        assert report["matrix"]["pdfbox-android"][api]["status"] == "not-executed"
    assert report["measurements"]["apk_delta_bytes"] == 12178
    assert report["measurements"]["apk_delta_status"] == "measured-candidate-vs-compile-only-baseline"
    assert set(report["matrix"]) == {"androidx-pdf", "pdfbox-android", "platform-pdf-renderer"}
    for candidate in report["matrix"].values():
        assert set(candidate) == {"30", "33", "35", "36"}
    assert PDFBOX in CONSUMER_BUILD.read_text()
    production_build = (ROOT / "document-pdf/build.gradle.kts").read_text()
    catalog = (ROOT / "gradle/libs.versions.toml").read_text()
    assert "libs.pdfbox.android" in production_build
    assert "libs.bcprov.jdk15to18" in production_build
    assert "libs.bcpkix.jdk15to18" in production_build
    assert "libs.bcutil.jdk15to18" in production_build
    assert "pdfboxAndroid = \"2.0.27.0\"" in catalog
    print("pdf qualification gate: PASS (defined production/development matrix qualified)")


if __name__ == "__main__":
    main()
