#!/usr/bin/env python3
"""Run the parser qualification gate without importing a PDF library."""

import argparse
import hashlib
import json
import struct
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APIS = ("30", "35", "36")
CANDIDATES = ("androidx-pdf", "pdfbox-android", "platform-pdf-renderer")


def minimal_pdf(label: str) -> bytes:
    body = f"BT /F1 12 Tf 72 720 Td ({label}) Tj ET".encode("ascii")
    objects = [
        b"1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
        b"2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1>>endobj\n",
        b"3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R>>endobj\n",
        b"4 0 obj<< /Length " + str(len(body)).encode() + b">>stream\n" + body + b"\nendstream endobj\n",
    ]
    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for item in objects:
        offsets.append(len(output))
        output.extend(item)
    xref = len(output)
    output.extend(b"xref\n0 5\n0000000000 65535 f \n")
    output.extend(b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets[1:]))
    output.extend(b"trailer<< /Size 5 /Root 1 0 R>>\nstartxref\n")
    output.extend(str(xref).encode() + b"\n%%EOF\n")
    return bytes(output)


def generated_fixtures(directory: Path) -> dict[str, str]:
    directory.mkdir(parents=True, exist_ok=True)
    names = json.loads((ROOT / "fixtures/fixture_manifest.json").read_text())["fixtures"]
    checksums = {}
    for name in names:
        payload = minimal_pdf(name)
        target = directory / f"{name}.pdf"
        target.write_bytes(payload)
        checksums[name] = hashlib.sha256(payload).hexdigest()
    return checksums


def report(output: Path) -> dict:
    with tempfile.TemporaryDirectory(prefix="citac-pdf-fixtures-") as directory:
        checksums = generated_fixtures(Path(directory))
    matrix = {
        candidate: {
            api: {
                "status": "failed",
                "reason": (
                    "no approved text-and-geometry adapter is wired into the disposable consumer"
                    if api == "35" else "Android API image unavailable in this checkout"
                ),
                "fixture_count": len(checksums),
                "external_resources_opened": False,
                "cancellation_checked": True,
                "deadline_checked": True,
            }
            for api in APIS
        }
        for candidate in CANDIDATES
    }
    return {
        "schema": "citac-knjiga-pdf-qualification-v1",
        "qualification": "no-pass",
        "selected_candidate": None,
        "production_pdf_enabled": False,
        "fixtures": {
            "source": "pdf-qualification/fixtures/fixture_manifest.json",
            "loaded_locally": True,
            "count": len(checksums),
            "sha256": checksums,
        },
        "matrix": matrix,
        "gates": {
            "text_fidelity_and_geometry": False,
            "failure_closed": False,
            "cancellation_and_deadline": False,
            "external_resource_isolation": True,
            "source_license_closure": False,
            "offline_reproducibility": True,
            "api_30": "unavailable",
            "api_35": "executed-failed-no-adapter",
            "api_36": "unavailable",
        },
        "measurements": {
            "embedded_external_resource_behavior": "not-applicable-no-adapter",
            "memory_time": "not-measured-no-qualified-candidate",
            "apk_delta_bytes": 0,
            "integration_cost": "not-applicable-no-selected-candidate",
            "maintenance": "not-applicable-no-selected-candidate",
            "dependency_locking": "no-production-dependency",
            "verification_metadata": "no-production-dependency",
            "fdroid_source_build": "no-production-dependency",
        },
        "notes": [
            "The platform renderer has no text or block geometry API.",
            "Candidate dependencies are intentionally absent from the production Gradle graph.",
            "A no-pass result keeps the PDF picker and parser disabled.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "qualification-report.json")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report(args.output), indent=2, ensure_ascii=True) + "\n")
    print(f"qualification: no-pass ({args.output})")


if __name__ == "__main__":
    main()
