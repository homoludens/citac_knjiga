package com.homoludens.citacknjiga.tts.onnx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

public class ModelPackageStoreTest {
    @Test
    public fun validPackageIsCopiedAndPublishedPrivately() {
        val root = createTempDirectory().toFile()
        val store = store(root)

        val installed = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        assertEquals("first", installed.packageId)
        assertEquals(installed, store.activePackage())
    }

    @Test
    public fun checksumFailureDoesNotReplaceActivePackage() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        val failure = try {
            store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("second", corruptModel = true)) })
            error("expected checksum failure")
        } catch (exception: ModelPackageImportException) {
            exception
        }

        assertEquals(ModelPackageFailureCode.CHECKSUM_MISMATCH, failure.code)
        assertEquals("first", store.activePackage()?.packageId)
    }

    @Test
    public fun incompatiblePackageDoesNotReplaceActivePackage() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        val failure = try {
            store.importPackage(
                ModelPackageSource {
                    ByteArrayInputStream(packageBytes("second", runtimeVersion = "9.9.9"))
                },
            )
            error("expected compatibility failure")
        } catch (exception: ModelPackageImportException) {
            exception
        }

        assertEquals(ModelPackageFailureCode.INCOMPATIBLE, failure.code)
        assertEquals("first", store.activePackage()?.packageId)
    }

    @Test
    public fun invalidActivePackageRollsBackToLastValidPackage() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })
        store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("second")) })
        File(root, "model-packages/active.zip").writeBytes(byteArrayOf(1, 2, 3))

        val active = store.activePackage()

        assertEquals("first", active?.packageId)
        assertTrue(root.resolve("model-packages/active.zip").isFile)
        assertEquals("first", store.activePackage()?.packageId)
    }

    @Test
    public fun corruptOnlyInstalledPackageDisablesOnnxSession() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("only")) })
        File(root, "model-packages/active.zip").writeBytes(byteArrayOf(1, 2, 3))

        val failure = assertThrows(ModelPackageImportException::class.java) {
            OnnxTtsSession.open(store)
        }

        assertEquals(ModelPackageFailureCode.NO_VALID_PACKAGE, failure.code)
    }

    @Test
    public fun readsVerifiedArtifactsByManifestRole() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        val installed = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        assertEquals("model-first".toByteArray().toList(), store.readArtifact(installed, "model").toList())
        assertEquals("voice".toByteArray().toList(), store.readArtifact(installed, "voice_style").toList())
    }

    @Test
    public fun streamsArtifactAndRemovesTemporaryFileAfterConsumerReturns() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        val installed = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })
        var temporary: File? = null

        val result = store.withVerifiedArtifactFile(installed, "model") { file ->
            temporary = file
            assertEquals("model-first", file.readText())
            "consumed"
        }

        assertEquals("consumed", result)
        assertFalse(temporary!!.exists())
        assertTrue(root.resolve("model-packages").listFiles().orEmpty().none { it.name.startsWith(".model-artifact-") })
    }

    @Test
    public fun removesTemporaryFileWhenArtifactConsumerFails() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        val installed = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })
        var temporary: File? = null

        assertThrows(IllegalStateException::class.java) {
            store.withVerifiedArtifactFile(installed, "model") { file ->
                temporary = file
                throw IllegalStateException("consumer failed")
            }
        }

        assertFalse(temporary!!.exists())
    }

    @Test
    public fun streamingArtifactRejectsChangedSizeOrChecksum() {
        val root = createTempDirectory().toFile()
        val store = store(root)
        val installed = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })
        root.resolve("model-packages/active.zip").writeBytes(packageBytes("first", corruptModel = true))

        val failure = assertThrows(ModelPackageImportException::class.java) {
            store.withVerifiedArtifactFile(installed, "model") { it.readBytes() }
        }

        assertEquals(ModelPackageFailureCode.CHECKSUM_MISMATCH, failure.code)
        assertTrue(root.resolve("model-packages").listFiles().orEmpty().none { it.name.startsWith(".model-artifact-") })
    }

    @Test
    public fun typedFailureResultUsesOnlyStableCategories() {
        val result = store(createTempDirectory().toFile()).tryImportPackage(
            ModelPackageSource { throw IllegalStateException("/private/raw/path") },
        )

        assertTrue(result is ModelPackageImportResult.Failure)
        assertEquals(ModelPackageFailureCode.STORAGE, (result as ModelPackageImportResult.Failure).failure.code)
        assertEquals(
            ModelPackageFailureCode.ERROR,
            ModelPackageStore.normalizeFailure(IllegalStateException("secret")).code,
        )
        assertTrue(result.toString().contains("STORAGE"))
        assertFalse(result.toString().contains("/private/raw/path"))
    }

    private fun store(root: File): ModelPackageStore = ModelPackageStore(
        filesDir = root,
        compatibility = ModelPackageCompatibility(minimumAndroidApi = 30),
    )

    private fun packageBytes(
        packageId: String,
        runtimeVersion: String = "1.29.0",
        corruptModel: Boolean = false,
    ): ByteArray {
        val payloads = linkedMapOf(
            "model.onnx" to "model-$packageId".toByteArray(),
            "voice/style.pt" to "voice".toByteArray(),
            "config/config.json" to "config".toByteArray(),
            "tests/vectors.json" to "vectors".toByteArray(),
            "notice/license.txt" to "license".toByteArray(),
            "notice/attribution.txt" to "attribution".toByteArray(),
            "tests/one.wav" to "audio".toByteArray(),
            "tests/two.wav" to "audio-two".toByteArray(),
        )
        val manifest = manifest(packageId, runtimeVersion, payloads)
        if (corruptModel) payloads["model.onnx"] = "corrupted".toByteArray()

        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
                payloads.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun manifest(
        packageId: String,
        runtimeVersion: String,
        payloads: Map<String, ByteArray>,
    ): String {
        val artifacts = payloads.entries.joinToString(",") { (path, bytes) ->
            val roles = when (path) {
                "model.onnx" -> "[\"model\"]"
                "voice/style.pt" -> "[\"voice_style\"]"
                "config/config.json" -> "[\"configuration\",\"vocabulary\"]"
                "tests/vectors.json" -> "[\"test_vector\"]"
                else -> "[\"notice\"]"
            }
            "{\"artifact_id\":${quote(path.replace('/', '-').replace('.', '-'))}," +
                "\"path\":${quote(path)},\"roles\":$roles," +
                "\"media_type\":\"application/octet-stream\",\"sha256\":${quote(sha256(bytes))}," +
                "\"size_bytes\":${bytes.size},\"required\":true," +
                "\"distribution_status\":\"allowed\",\"license_refs\":[\"license\"]," +
                "\"attribution_refs\":[\"attribution\"]}"
        }
        val identity = sha256(
            buildString {
                append("{\"artifacts\":[")
                payloads.keys.sorted().joinTo(this, separator = ",") { path ->
                    val bytes = payloads.getValue(path)
                    "{\"path\":${quote(path)},\"sha256\":${quote(sha256(bytes))}}"
                }
                append("],\"package_id\":${quote(packageId)},\"package_version\":\"1.0.0\"}")
            }.toByteArray(),
        )

        return """
            {"schema":{"id":"serbian-model-package","version":1},"manifest":{"package_id":${quote(packageId)},"package_version":"1.0.0","manifest_path":"manifest.json","created_at":"2026-08-27T12:00:00Z","canonicalization":"json-sorted-keys-utf8-v1","identity":{"algorithm":"sha-256","value":${quote(identity)},"input":"package_id, package_version, and sorted artifact path+sha256 pairs"}},"artifacts":[$artifacts],"model":{"model_id":"$packageId","revision":"test","artifact_id":"model-onnx","format":"onnx","architecture":{"family":"kokoro-82m","config_artifact_id":"config-config-json"},"output_contract":{"waveform":{"sample_rate_hz":24000}}},"voice_style":{"artifact_id":"voice-style-pt","locale":"sr","shape":[510,1,256]},"vocabulary":{"artifact_id":"config-config-json"},"configuration":{"artifact_id":"config-config-json","sample_rate_hz":24000},"preprocessing":{"compatibility_id":"kokoro-sr-ca5590d9","contract_version":1,"locale":"sr"},"runtime":{"version":${quote(runtimeVersion)},"platform":"android","min_android_api":30,"abis":["arm64-v8a"],"execution_provider":"cpu","threading":{"intra_op_threads":1,"inter_op_threads":1}},"test_vectors":{"manifest_artifact_id":"tests-vectors-json"},"licenses":[],"attribution":[],"legal":{}}
        """.trimIndent()
    }

    private fun quote(value: String): String = "\"$value\""

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
