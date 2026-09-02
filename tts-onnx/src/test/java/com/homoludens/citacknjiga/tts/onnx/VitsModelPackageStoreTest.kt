package com.homoludens.citacknjiga.tts.onnx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class VitsModelPackageStoreTest {
    @Test
    public fun validPackageUsesTheSeparateVitsSlotAndVerifiedTokenArtifact() {
        val root = createTempDirectory().toFile()
        val store = VitsModelPackageStore(root)

        val installed = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        assertEquals("vits", installed.engine)
        assertEquals("tokens", store.withVerifiedArtifactFile(installed, "tokens") { it.readText() })
        assertTrue(root.resolve("model-packages/vits-active.zip").isFile)
        assertTrue(!root.resolve("model-packages/active.zip").exists())
    }

    @Test
    public fun packageMissingTokensIsRejectedWithoutReplacingVitsState() {
        val root = createTempDirectory().toFile()
        val store = VitsModelPackageStore(root)
        val first = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        assertTrue(runCatching {
            store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("second", includeTokens = false)) })
        }.isFailure)
        assertEquals(first, store.activePackage())
    }

    @Test
    public fun releaseVersionMismatchDoesNotReplaceVitsState() {
        val root = createTempDirectory().toFile()
        val store = VitsModelPackageStore(root)
        val first = store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("first")) })

        val failure = runCatching {
            store.importPackage(
                ModelPackageSource { ByteArrayInputStream(packageBytes("second")) },
                expectedPackageVersion = "2.0.0",
            )
        }.exceptionOrNull()

        assertTrue(failure is ModelPackageImportException)
        assertEquals(ModelPackageFailureCode.INCOMPATIBLE, (failure as ModelPackageImportException).code)
        assertEquals(first, store.activePackage())
    }

    @Test
    public fun failedVitsImportLeavesKokoroPackageSlotsUntouched() {
        val root = createTempDirectory().toFile()
        val directory = root.resolve("model-packages").apply { mkdirs() }
        val kokoroActive = "kokoro-active".toByteArray()
        val kokoroPrevious = "kokoro-previous".toByteArray()
        directory.resolve("active.zip").writeBytes(kokoroActive)
        directory.resolve("last-valid.zip").writeBytes(kokoroPrevious)
        val store = VitsModelPackageStore(root)

        assertTrue(runCatching {
            store.importPackage(ModelPackageSource { ByteArrayInputStream(packageBytes("failed", includeTokens = false)) })
        }.isFailure)

        assertArrayEquals(kokoroActive, directory.resolve("active.zip").readBytes())
        assertArrayEquals(kokoroPrevious, directory.resolve("last-valid.zip").readBytes())
        assertTrue(!directory.resolve("vits-active.zip").exists())
    }

    @Test
    public fun alteredRawOrUndeclaredEntriesAreRejected() {
        val root = createTempDirectory().toFile()
        val store = VitsModelPackageStore(root)

        assertTrue(runCatching {
            store.importPackage(ModelPackageSource {
                ByteArrayInputStream(packageBytes("raw", extraPath = "checkpoint.pth"))
            })
        }.isFailure)
        assertTrue(runCatching {
            store.importPackage(ModelPackageSource {
                ByteArrayInputStream(packageBytes("undeclared", undeclaredPath = "extra.bin"))
            })
        }.isFailure)
        assertTrue(runCatching {
            store.importPackage(ModelPackageSource {
                ByteArrayInputStream(packageBytes("altered", corruptModel = true))
            })
        }.isFailure)
    }

    @Test
    public fun packageWithoutRequiredAttributionEntriesIsRejected() {
        val store = VitsModelPackageStore(createTempDirectory().toFile())

        assertTrue(runCatching {
            store.importPackage(ModelPackageSource {
                ByteArrayInputStream(packageBytes("missing-legal", includeLegalEntries = false))
            })
        }.isFailure)
    }

    private fun packageBytes(
        id: String,
        includeTokens: Boolean = true,
        includeLegalEntries: Boolean = true,
        extraPath: String? = null,
        undeclaredPath: String? = null,
        corruptModel: Boolean = false,
    ): ByteArray {
        val payloads = linkedMapOf(
            "model.onnx" to "model-$id".toByteArray(),
            "config.json" to "{}".toByteArray(),
        )
        if (includeLegalEntries) {
            payloads["notice.json"] = "notice".toByteArray()
            payloads["attribution.json"] = "attribution".toByteArray()
        }
        if (includeTokens) payloads["tokens.txt"] = "tokens".toByteArray()
        extraPath?.let { payloads[it] = "raw".toByteArray() }
        val entries = payloads.entries.joinToString(",") { (path, bytes) ->
            "{\"path\":\"$path\",\"role\":\"${role(path)}\",\"sha256\":\"${sha256(bytes)}\",\"size_bytes\":${bytes.size}}"
        }
        val manifest = """
            {"schema":"serbian-vits-model-package:1","version":"1.0.0","identity_sha256":"${"0".repeat(64)}",
            "candidate":{"model_id":"daremc86/sr-cv-vits","revision":"83dc1e1b95d85b9f5602dc94909706fc83dfbc6c","speaker":{"label":"Dragana","id":0}},
            "entries":[$entries],"declared_entries":[${payloads.keys.joinToString(",") { "\"$it\"" }}],
            "graph_contract":{"status":"INSPECTED","inputs":[],"outputs":[],"operator_domains":["ai.onnx"],"external_data":false,"network_access":false},
            "preprocessing":{"identity":"serbian-vits-preprocessing-v1","unsupported_input":"diagnostic"},
            "resampler":{"identity":"serbian-vits-resampler-v1","native_rate_hz":22050,"final_rate_hz":24000,"channels":1},
            "legal":"ALLOWED","attribution":{"license":"CC-BY-4.0","source_url":"https://huggingface.co/daremc86/sr-cv-vits/tree/83dc1e1b95d85b9f5602dc94909706fc83dfbc6c","modification_notice":"Converted for offline use."},
            "qualification":{"status":"PASS","api":33,"abi":"arm64-v8a"},"evidence_hashes":{}
            }
        """.trimIndent().replace("\n", "")
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
                payloads.forEach { (path, bytes) ->
                    val published = if (corruptModel && path == "model.onnx") "altered".toByteArray() else bytes
                    zip.putNextEntry(ZipEntry(path)); zip.write(published); zip.closeEntry()
                }
                undeclaredPath?.let { path ->
                    zip.putNextEntry(ZipEntry(path)); zip.write("undeclared".toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun role(path: String): String = when {
        path.endsWith(".onnx") -> "onnx"
        path == "tokens.txt" -> "tokens"
        path == "config.json" -> "configuration"
        path == "attribution.json" -> "attribution"
        else -> "notice"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
