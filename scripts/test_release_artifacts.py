#!/usr/bin/env python3
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.release_artifacts import (
    ReleaseError,
    parse_badging,
    scan_apk_payload,
    sha256,
    validate_artifact_directory,
    verify_checksums,
    verify_signature,
    network_policy,
)


class ReleaseArtifactChecksTest(unittest.TestCase):
    def test_release_policy_lists_only_pinned_model_assets_and_offline_operations(self):
        policy = network_policy()
        self.assertEqual(policy["permission"], "android.permission.INTERNET")
        self.assertFalse(policy["cleartext"])
        self.assertEqual(len(policy["allowed_assets"]), 2)
        self.assertEqual(
            policy["offline_operations"],
            ["document_import", "generation", "runtime_dependency_acquisition"],
        )
        self.assertTrue(all(url.startswith("https://github.com/homoludens/citac_knjiga/releases/download/")
                            for url in (asset["url"] for asset in policy["allowed_assets"])))

    def test_version_metadata_is_parsed(self):
        self.assertEqual(
            parse_badging("package: name='com.example' versionCode='7' versionName='1.2.3'"),
            {"application_id": "com.example", "version_code": "7", "version_name": "1.2.3"},
        )

    def test_model_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/model.onnx", b"not a model")
            with self.assertRaisesRegex(ReleaseError, "forbidden model/audio"):
                scan_apk_payload(apk)

    def test_secret_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"-----BEGIN PRIVATE KEY-----")
            with self.assertRaisesRegex(ReleaseError, "possible secret"):
                scan_apk_payload(apk)

    def test_checksums_detect_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            apk = output / "citac-knjiga-standard-v0.1.0.apk"
            apk.write_bytes(b"release")
            digest = hashlib.sha256(b"release").hexdigest()
            (output / "SHA256SUMS").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
            verify_checksums(output)
            apk.write_bytes(b"tampered")
            with self.assertRaisesRegex(ReleaseError, "checksum mismatch"):
                verify_checksums(output)

    def test_signature_identity_and_scheme_are_verified(self):
        output = """Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=release
Signer #1 certificate SHA-256 digest: AA:BB:CC
"""
        with patch("scripts.release_artifacts.run", return_value=output):
            result = verify_signature(Path("app.apk"), Path("apksigner"))
        self.assertEqual(result["status"], "signed")
        self.assertTrue(result["schemes"]["v2"])
        self.assertEqual(result["certificate_sha256"], "aabbcc")

    def test_release_directory_rejects_separate_model_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "citac-knjiga-standard-v0.1.0.apk").write_bytes(b"apk")
            (output / "citac-knjiga-fdroid-v0.1.0-fdroid.apk").write_bytes(b"apk")
            (output / "SHA256SUMS").write_text("", encoding="utf-8")
            (output / "release-manifest.json").write_text("{}", encoding="utf-8")
            (output / "model-package.zip").write_bytes(b"model")
            with self.assertRaisesRegex(ReleaseError, "non-release payloads"):
                validate_artifact_directory(output, allow_unsigned=False)


if __name__ == "__main__":
    unittest.main()
