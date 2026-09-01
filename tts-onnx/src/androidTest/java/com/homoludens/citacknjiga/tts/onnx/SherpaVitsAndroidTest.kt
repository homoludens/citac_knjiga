package com.homoludens.citacknjiga.tts.onnx

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

public class SherpaVitsAndroidTest {
    @Test
    public fun qualifiedPackageGeneratesOfflineSerbianAudio() {
        val packagePath = InstrumentationRegistry.getArguments().getString("vitsPackage")
        assumeTrue("vitsPackage argument is required for production qualification", packagePath != null)
        val packageFile = File(requireNotNull(packagePath))
        assumeTrue("external VITS package is missing", packageFile.isFile)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VitsModelPackageStore(context.filesDir)
        val installed = store.importPackage(ModelPackageSource { packageFile.inputStream() })
        val vocabulary = readVocabulary(store, installed)
        val frontend = VitsSerbianFrontend(vocabulary, blankId = 139)
        val prepared = frontend.process("Dobar dan.")

        SherpaVitsSession.open(store, installed).use { session ->
            val native = session.generate(prepared.tokenIds.toIntArray(), installed.speakerId ?: 0, 1f)
            assertEquals(22_050, native.sampleRateHz)
            assertEquals(1, native.channels)
            assertTrue(native.pcm.isNotEmpty())
            assertTrue(native.pcm.any { kotlin.math.abs(it) > 0.0001f })
            val final = VitsAudioOutputValidator.resampleOnce(native)
            assertEquals(24_000, final.sampleRateHz)
            assertEquals(1, final.channels)
            assertTrue(final.pcm.isNotEmpty())
        }
    }

    private fun readVocabulary(
        store: VitsModelPackageStore,
        packageInfo: InstalledModelPackage,
    ): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        store.withVerifiedArtifactFile(packageInfo, "tokens") { file ->
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val fields = line.split(" ", limit = 2)
                if (fields.size == 1) {
                    result[' '.code] = fields[0].toInt()
                } else if (!fields[0].startsWith("<")) {
                    result[fields[0].first().code] = fields[1].toInt()
                }
            }
        }
        return result
    }
}
