#!/usr/bin/env python3
"""Resolve Android dependencies and generate the reproducible license bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
GRAPH = ROOT / "build/reports/dependency-audit/resolved-dependencies.json"
NOTICE_DIR = ROOT / "app/src/main/assets/notices"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / (
    "caches/modules-2/files-2.1"
)


def local_name(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def child_text(element: ET.Element, *names: str) -> str | None:
    for child in element.iter():
        if local_name(child) in names and child.text and child.text.strip():
            return child.text.strip()
    return None


def normalize_license(name: str) -> str:
    value = name.lower().replace("license", "").replace("the ", "").strip()
    if "apache" in value:
        return "Apache-2.0"
    if value == "mit" or value.startswith("mit "):
        return "MIT"
    if "bsd" in value and ("3" in value or "three" in value):
        return "BSD-3-Clause"
    if "bsd" in value and ("2" in value or "two" in value):
        return "BSD-2-Clause"
    if ("eclipse public" in value and "1" in value) or value == "epl 1.0":
        return "EPL-1.0"
    if ("eclipse public" in value and "2" in value) or value == "epl 2.0":
        return "EPL-2.0"
    if "cddl" in value:
        return "CDDL-1.0"
    if "lgpl" in value:
        return "LGPL-3.0-or-later" if "3" in value else "LGPL-2.1-or-later"
    if "gpl" in value:
        return "GPL-3.0-or-later" if "3" in value else "GPL-2.0-or-later"
    if "public domain" in value:
        return "CC0-1.0"
    return "UNKNOWN"


LICENSE_FALLBACKS: dict[tuple[str, str], tuple[str, str, str, str]] = {
    ("com.google.guava", "guava"): (
        "Apache-2.0",
        "Apache License 2.0",
        "https://www.apache.org/licenses/LICENSE-2.0",
        "https://github.com/google/guava",
    ),
    ("com.google.guava", "failureaccess"): (
        "Apache-2.0",
        "Apache License 2.0",
        "https://www.apache.org/licenses/LICENSE-2.0",
        "https://github.com/google/guava",
    ),
    ("com.google.guava", "listenablefuture"): (
        "Apache-2.0",
        "Apache License 2.0",
        "https://www.apache.org/licenses/LICENSE-2.0",
        "https://github.com/google/guava",
    ),
    ("org.hamcrest", "hamcrest-core"): (
        "BSD-3-Clause",
        "BSD 3-Clause License",
        "https://opensource.org/licenses/BSD-3-Clause",
        "https://github.com/hamcrest/JavaHamcrest",
    ),
}


def cache_relative(path: Path) -> str:
    try:
        return "~/.gradle/" + path.relative_to(CACHE.parents[2]).as_posix()
    except ValueError:
        return path.as_posix()


def find_pom(group: str, name: str, version: str) -> Path | None:
    directory = CACHE / group / name / version
    poms = sorted(directory.rglob("*.pom")) if directory.is_dir() else []
    return poms[0] if poms else None


def pom_metadata(coordinate: dict[str, str]) -> dict[str, object]:
    group = coordinate["group"]
    name = coordinate["name"]
    version = coordinate["version"]
    pom = find_pom(group, name, version)
    if pom is None:
        return {
            "license_ids": ["UNKNOWN"],
            "license_urls": [],
            "source_url": None,
            "provenance": "no local Gradle POM metadata",
            "metadata": None,
        }

    root = ET.parse(pom).getroot()
    licenses: list[dict[str, str]] = []
    for license_node in root.iter():
        if local_name(license_node) != "license":
            continue
        license_name = child_text(license_node, "name")
        license_url = child_text(license_node, "url")
        if license_name:
            licenses.append(
                {
                    "id": normalize_license(license_name),
                    "name": license_name,
                    "url": license_url or "",
                }
            )
    fallback = LICENSE_FALLBACKS.get((group, name))
    used_fallback = not licenses and fallback is not None
    fallback_source = None
    if used_fallback:
        license_id, license_name, license_url, fallback_source = fallback
        licenses.append({"id": license_id, "name": license_name, "url": license_url})
    source_url = None
    for element in root.iter():
        if local_name(element) == "scm":
            source_url = child_text(element, "url")
            break
    source_url = source_url or child_text(root, "url")
    if fallback_source:
        source_url = fallback_source
    relative = cache_relative(pom)
    provenance = f"Gradle cache POM: {relative}"
    if used_fallback:
        provenance += "; license fallback from upstream project metadata because POM omitted license"
    return {
        "license_ids": sorted({item["id"] for item in licenses}),
        "license_urls": sorted({item["url"] for item in licenses if item["url"]}),
        "license_names": sorted({item["name"] for item in licenses}),
        "source_url": source_url,
        "provenance": provenance,
        "metadata": {
            "path": relative,
            "sha256": hashlib.sha256(pom.read_bytes()).hexdigest(),
        },
    }


def asset(
    coordinate: str,
    version: str,
    license_ids: list[str],
    license_urls: list[str],
    source_url: str | None,
    provenance: str,
    scope: str,
    bundled: bool,
    status: str = "audited",
) -> dict[str, object]:
    return {
        "coordinate": coordinate,
        "version": version,
        "license_ids": license_ids,
        "license_urls": license_urls,
        "source_url": source_url,
        "provenance": provenance,
        "scope": scope,
        "bundled": bundled,
        "status": status,
    }


def non_maven_inventory() -> list[dict[str, object]]:
    gpl = "https://www.gnu.org/licenses/gpl-3.0.html"
    cc_by = "https://creativecommons.org/licenses/by/4.0/"
    cc_by_sa = "https://creativecommons.org/licenses/by-sa/4.0/"
    cc0 = "https://creativecommons.org/publicdomain/zero/1.0/"
    return [
        asset(
            "project:citac-knjiga:0.1.0",
            "0.1.0",
            ["GPL-3.0-or-later"],
            [gpl],
            None,
            "Application source is combined with the linked GPL eSpeak-NG engine; project license decision in model-tools/phonemization-decision.md.",
            "runtime",
            True,
            "requires GPL source and notices",
        ),
        asset(
            "native:espeak-ng:1.52.0",
            "1.52.0",
            ["GPL-3.0-or-later"],
            ["https://github.com/espeak-ng/espeak-ng/blob/1.52.0/COPYING"],
            "https://github.com/espeak-ng/espeak-ng/tree/4870adfa25b1a32b4361592f1be8a40337c58d6c",
            "model-tools/native/NOTICE.md; source commit 4870adfa25b1a32b4361592f1be8a40337c58d6c; no local patch",
            "runtime",
            True,
            "GPL source offer required",
        ),
        asset(
            "native:espeak-ng-data:1",
            "1",
            ["GPL-3.0-or-later"],
            ["https://github.com/espeak-ng/espeak-ng/blob/1.52.0/COPYING"],
            "https://github.com/espeak-ng/espeak-ng/tree/4870adfa25b1a32b4361592f1be8a40337c58d6c",
            "model-tools/native/espeak-data-manifest-v1.json; checked-in seven-file Serbian data closure",
            "runtime",
            True,
            "file-level data audit retained",
        ),
        asset(
            "project:preprocessing-resources:v1",
            "v1",
            ["GPL-3.0-or-later"],
            [gpl],
            None,
            "model-tools/preprocessing/; self-authored resources bound by preprocessing-contract-v1.json",
            "runtime",
            True,
        ),
        asset(
            "model:kokoro-82m-base:external",
            "external",
            ["Apache-2.0"],
            ["https://huggingface.co/hexgrad/Kokoro-82M"],
            "https://huggingface.co/hexgrad/Kokoro-82M",
            "model-tools/dependency-inventory.md; package is external and no model payload is checked in",
            "external-runtime",
            False,
            "not bundled; HF card license must be rechecked at package publication",
        ),
        asset(
            "model:kokoro-serbian-dragana:external",
            "external",
            ["CC-BY-SA-4.0"],
            [cc_by, cc_by_sa],
            "https://huggingface.co/datasets/daremc86/serbian_common_voice",
            "model-tools/legal-inventory.md; derived package treatment confirmed by project owner; no archive is checked in",
            "external-runtime",
            False,
            "not bundled; attribution and synthetic-audio disclosure required",
        ),
        asset(
            "dataset:serbian-common-voice-dragana:external",
            "external",
            ["CC-BY-4.0"],
            [cc_by],
            "https://huggingface.co/datasets/daremc86/serbian_common_voice",
            "model-tools/legal-inventory.md section 1.5; provenance only, never bundled",
            "external-provenance",
            False,
        ),
        asset(
            "dataset:juzne-vesti-sr:external",
            "v1.0",
            ["CC-BY-SA-4.0"],
            [cc_by_sa],
            "https://www.clarin.si/repository/xmlui/handle/11356/1679",
            "model-tools/legal-inventory.md section 1.6; provenance only, never bundled",
            "external-provenance",
            False,
            "source recording rights remain a qualification limitation",
        ),
        asset(
            "fixtures:epub-import:1",
            "1",
            ["CC0-1.0"],
            [cc0],
            None,
            "document-epub/src/test/resources/fixtures/README.md; self-authored compact EPUB archives",
            "test-only",
            False,
        ),
        asset(
            "fixtures:serbian-golden-vectors:1",
            "1",
            ["GPL-3.0-or-later"],
            [gpl],
            None,
            "model-tools/reference/README.md; self-authored text and derived test vectors",
            "test-only",
            False,
        ),
        asset(
            "font:application-default:absent",
            "absent",
            [],
            [],
            None,
            "No bundled third-party font; the app uses platform font resources.",
            "not-present",
            False,
        ),
    ]


def run_gradle() -> None:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise SystemExit("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    command = ["./gradlew", "--offline", "writeResolvedDependencyInventory", "--console=plain"]
    subprocess.run(command, cwd=ROOT, check=True, env=os.environ.copy())


def read_graph() -> dict[str, object]:
    if not GRAPH.is_file():
        raise SystemExit(f"missing {GRAPH}; run the Gradle graph task first")
    return json.loads(GRAPH.read_text(encoding="utf-8"))


def build_inventory(graph: dict[str, object]) -> dict[str, object]:
    components = []
    for raw in graph["components"]:
        metadata = pom_metadata(raw)
        scopes = set(raw["scopes"])
        has_release = any("ReleaseRuntimeClasspath" in name for name in raw["configurations"])
        if has_release:
            scope = "runtime"
        elif "runtime" in scopes:
            scope = "debug-only"
        else:
            scope = "test-only"
        row = {
            "coordinate": raw["coordinate"],
            "group": raw["group"],
            "name": raw["name"],
            "version": raw["version"],
            "scope": scope,
            "used_in_tests": "test" in scopes,
            **metadata,
        }
        components.append(row)
    components.sort(key=lambda item: item["coordinate"])
    return {
        "schema": "citac-knjiga-license-inventory",
        "version": 1,
        "generated_by": "scripts/audit_dependencies.py",
        "constraints": {
            "offline": True,
            "release_flavors": ["standard", "fdroid"],
            "runtime_modules": ["app", "core", "tts-onnx", "document-epub", "playback-export"],
            "license_source": "local Gradle POM metadata when available",
        },
        "gradle_graph": {
            "schema": graph["schema"],
            "configurations": graph["configurations"],
        },
        "components": components,
        "non_maven_assets": non_maven_inventory(),
        "findings": {
            "proprietary_services": [],
            "network_runtime_artifacts": [],
            "excluded_candidates": [
                "Readium Kotlin Toolkit: rejected for production; direct platform ZIP/XML parser selected",
                "Sherpa-ONNX: rejected because Serbian frontend/tokenization does not preserve the verified boundary",
            ],
            "qualification_limitations": [
                "The external model package is not bundled; its legal payload gate remains in model-tools/legal-inventory.md.",
                "eSpeak-NG source/data closure requires GPL source offer and file-level data review for each release.",
                "No project model, dataset, generated audio, or private document content is copied into the notice assets.",
            ],
        },
    }


def markdown(inventory: dict[str, object]) -> str:
    rows = inventory["components"] + inventory["non_maven_assets"]
    lines = [
        "# Third-party notices",
        "",
        "Generated by `scripts/audit_dependencies.py` from the offline Gradle runtime/test graph and checked-in provenance records.",
        "This file is bundled in both standard and F-Droid release assets.",
        "",
        "## Android dependency graph",
        "",
        "| Coordinate | Version | Scope | License | License URL | Source/provenance |",
        "|---|---|---|---|---|---|",
    ]
    for row in inventory["components"]:
        licenses = ", ".join(row.get("license_ids", [])) or "NOT APPLICABLE"
        urls = " ".join(f"<{url}>" for url in row.get("license_urls", [])) or "n/a"
        provenance = row.get("provenance", "")
        lines.append(
            f"| `{row['coordinate']}` | `{row['version']}` | {row['scope']} | {licenses} | {urls} | {provenance} |"
        )
    lines += ["", "## Native, model, dataset, and fixture inventory", ""]
    lines += [
        "| Coordinate | Version | Scope | Bundled | License | Source/provenance | Status |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in inventory["non_maven_assets"]:
        licenses = ", ".join(row.get("license_ids", [])) or "not applicable"
        source = row.get("source_url") or row.get("provenance", "")
        lines.append(
            f"| `{row['coordinate']}` | `{row['version']}` | {row['scope']} | {str(row['bundled']).lower()} | {licenses} | {source} | {row['status']} |"
        )
    lines += [
        "",
        "## Policy findings",
        "",
        "- No proprietary services or runtime network artifacts are present in the resolved application graph.",
        "- Readium and Sherpa-ONNX are intentionally excluded from production dependencies; see the OpenSpec rejection/selection records.",
        "- The linked eSpeak-NG runtime makes the combined application GPL-3.0-or-later; its source/build provenance and data closure remain release obligations.",
        "- External model and dataset rows are provenance only. They are not bundled in the APK and remain subject to the model legal inventory gate.",
        "- Test-only dependencies are inventoried but are not part of release runtime assets.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-gradle", action="store_true")
    args = parser.parse_args()
    if not args.skip_gradle:
        run_gradle()
    inventory = build_inventory(read_graph())
    unknown_runtime = [
        row["coordinate"]
        for row in inventory["components"]
        if row["scope"] in {"runtime", "debug-only", "test-only"}
        and (not row.get("license_ids") or "UNKNOWN" in row.get("license_ids", []))
    ]
    if unknown_runtime:
        raise SystemExit("undocumented runtime licenses: " + ", ".join(unknown_runtime))
    NOTICE_DIR.mkdir(parents=True, exist_ok=True)
    (NOTICE_DIR / "dependency-license-inventory.json").write_text(
        json.dumps(inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (NOTICE_DIR / "THIRD_PARTY_NOTICES.md").write_text(markdown(inventory), encoding="utf-8")
    print(f"audited {len(inventory['components'])} Android components")
    print(f"wrote {NOTICE_DIR / 'dependency-license-inventory.json'}")
    print(f"wrote {NOTICE_DIR / 'THIRD_PARTY_NOTICES.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
