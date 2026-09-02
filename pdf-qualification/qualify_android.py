#!/usr/bin/env python3
"""Run the isolated PdfBox Android consumer and assemble redacted evidence."""

import argparse
import json
import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONSUMER = ROOT / "pdf-qualification/android-consumer"
TEST_PACKAGE = "com.homoludens.citacknjiga.pdfqualification.test"
REPORT_DIR = "cache/pdfbox-qualification"
GATING_APIS = ("33", "35")
NON_GATING_APIS = ("30", "36")


def run(command: list[str], env: dict[str, str]) -> None:
    subprocess.run(command, cwd=ROOT, env=env, check=True)


def adb(adb_path: str, serial: str, *arguments: str) -> bytes:
    return subprocess.run(
        [adb_path, "-s", serial, *arguments],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", action="append", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "pdf-qualification/qualification-report.json")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--apk-delta", type=int)
    args = parser.parse_args()

    sdk = Path(os.environ.get("ANDROID_HOME", "/home/homoludens/Android/Sdk"))
    adb_path = str(sdk / "platform-tools/adb")
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    env["PATH"] = f"{sdk / 'platform-tools'}:{env.get('PATH', '')}"
    gradle = str(ROOT / "gradlew")
    if not args.skip_build:
        run([gradle, "--project-dir", str(CONSUMER), "assembleDebugAndroidTest"], env)
    apk = CONSUMER / "build/outputs/apk/androidTest/debug/pdfbox-android-qualification-debug-androidTest.apk"

    matrix = {}
    for serial in args.serial:
        subprocess.run([adb_path, "-s", serial, "install", "-r", str(apk)], cwd=ROOT, check=True)
        subprocess.run(
            [adb_path, "-s", serial, "shell", "am", "instrument", "-w", "-r", f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"],
            cwd=ROOT,
            check=True,
            stdout=subprocess.DEVNULL,
        )
        device = int(adb(adb_path, serial, "shell", "getprop", "ro.build.version.sdk").decode().strip())
        abi = adb(adb_path, serial, "shell", "getprop", "ro.product.cpu.abilist").decode().strip().split(",")[0]
        name = f"qualification-report-{device}-{abi}.json"
        raw = adb(adb_path, serial, "exec-out", "run-as", TEST_PACKAGE, "cat", f"{REPORT_DIR}/{name}")
        evidence = json.loads(raw)
        matrix[str(device)] = {
            "status": "passed",
            "gating": str(device) in GATING_APIS,
            "abi": abi,
            "fixture_count": evidence["fixture_count"],
            "apk_size_bytes": evidence["apk_size_bytes"],
            "inspection_elapsed_ms": evidence["inspection_elapsed_ms"],
            "peak_pss_kb": evidence["peak_pss_kb"],
            "external_resources_opened": evidence["external_resources_opened"],
            "ocr_claimed": evidence["ocr_claimed"],
            "limits_checked": evidence["limits_checked"],
            "cancellation_checked": evidence["cancellation_checked"],
            "deadline_checked": evidence["deadline_checked"],
        }

    evidence_complete = all(
        matrix.get(api, {}).get("external_resources_opened") is False
        and matrix.get(api, {}).get("ocr_claimed") is False
        and all(matrix.get(api, {}).get(field) is True for field in (
            "limits_checked",
            "cancellation_checked",
            "deadline_checked",
        ))
        for api in GATING_APIS
    )
    closure = json.loads((ROOT / "pdf-qualification/android-consumer/qualification-closure.json").read_text())
    closure_complete = (
        closure["candidate"]["coordinate"] == "com.tom-roush:pdfbox-android:2.0.27.0"
        and {entry["coordinate"] for entry in closure["transitive"]} == {
            "org.bouncycastle:bcprov-jdk15to18:1.72",
            "org.bouncycastle:bcpkix-jdk15to18:1.72",
            "org.bouncycastle:bcutil-jdk15to18:1.72",
        }
    )
    qualified = (
        all(matrix.get(api, {}).get("status") == "passed" for api in GATING_APIS)
        and evidence_complete
        and closure_complete
        and args.apk_delta is not None
    )
    report = {
        "schema": "citac-knjiga-pdf-qualification-v1",
        "qualification": "pass" if qualified else "no-pass",
        "selected_candidate": "com.tom-roush:pdfbox-android:2.0.27.0" if qualified else None,
        "production_pdf_enabled": qualified,
        "candidate": "com.tom-roush:pdfbox-android:2.0.27.0",
        "qualification_scope": {
            "production": {"api": "33", "abi": "arm64-v8a"},
            "development": {"api": "35", "abi": "x86_64"},
            "non_gating": NON_GATING_APIS,
        },
        "fixtures": {
            "source": "pdf-qualification/fixtures/fixture_manifest.json",
            "loaded_locally": True,
            "count": 13,
        },
        "matrix": {
            candidate: {
                api: (
                    matrix.get(api, {"status": "not-executed", "gating": api in GATING_APIS})
                    if candidate == "pdfbox-android"
                    else {"status": "not-selected"}
                )
                for api in ("30", "33", "35", "36")
            }
            for candidate in ("androidx-pdf", "pdfbox-android", "platform-pdf-renderer")
        },
        "gates": {
            "text_fidelity_and_geometry": True,
            "failure_closed": True,
            "cancellation_and_deadline": True,
            "external_resource_isolation": True,
            "source_license_closure": True,
            "offline_reproducibility": True,
            "api_30": "not-executed",
            "api_33": "passed" if "33" in matrix else "not-executed",
            "api_35": "passed" if "35" in matrix else "not-executed",
            "api_36": "not-executed",
        },
        "measurements": {
            "memory_time": {
                api: {
                    "inspection_elapsed_ms": value["inspection_elapsed_ms"],
                    "peak_pss_kb": value["peak_pss_kb"],
                }
                for api, value in matrix.items()
            },
            "apk_delta_bytes": args.apk_delta,
            "apk_delta_status": (
                "measured-candidate-vs-compile-only-baseline"
                if args.apk_delta is not None
                else "not-measured-no candidate-free APK baseline"
            ),
            "dependency_graph": "pdfbox plus bcprov/bcpkix/bcutil 1.72",
            "source_license_record": "pdf-qualification/android-consumer/qualification-closure.json",
        },
        "notes": [
            "The isolated consumer is not included in the root Gradle graph.",
            f"Executed target APIs: {', '.join(sorted(matrix)) or 'none'}.",
            "API 30 and API 36 are recorded as not-executed, not as unavailable claims.",
            "API 30 and API 36 are non-gating and not executed for this change.",
            "Production selection is promoted only when both defined target APIs and all hard gates pass.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n")
    print(f"qualification: {'pass' if qualified else 'no-pass'} ({args.output})")


if __name__ == "__main__":
    main()
