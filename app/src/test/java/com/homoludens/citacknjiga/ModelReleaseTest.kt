package com.homoludens.citacknjiga

import com.homoludens.citacknjiga.diagnostics.ModelReleaseAction
import com.homoludens.citacknjiga.diagnostics.ModelReleaseDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

public class ModelReleaseTest {
    @Test
    public fun acceptsOnlyAbsoluteHttpUrlsWithHosts() {
        assertNotNull(ModelReleaseAction.validatedUrl("https://example.com/releases/model"))
        assertNotNull(ModelReleaseAction.validatedUrl("http://example.com/model"))
    }

    @Test
    public fun rejectsEmptyAlternateMalformedAndCredentialBearingUrls() {
        listOf(
            "",
            "file:///model.zip",
            "content://model",
            "intent://model",
            "javascript:alert(1)",
            "https://user:password@example.com/model",
            "https:///model",
            "not a url",
        ).forEach { assertNull(it, ModelReleaseAction.validatedUrl(it)) }
    }

    @Test
    public fun exposesPinnedKokoroAndVitsReleaseDescriptors() {
        assertEquals(2, ModelReleaseDescriptor.ALL.size)

        val kokoro = ModelReleaseDescriptor.KOKORO
        assertEquals("KOKORO", kokoro.engine.name)
        assertEquals("homoludens", kokoro.repositoryOwner)
        assertEquals("citac_knjiga", kokoro.repositoryName)
        assertEquals("https://github.com/homoludens/citac_knjiga/releases/download/" +
            "kokoro-model-v1.0.0/kokoro-serbian-dragana-v2.zip", kokoro.assetUrl)
        assertEquals("kokoro-model-v1.0.0", kokoro.releaseTag)
        assertEquals("kokoro-serbian-dragana-v2.zip", kokoro.assetFileName)
        assertEquals("1.0.0", kokoro.version)
        assertEquals(338_316_574L, kokoro.expectedSizeBytes)
        assertEquals("58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b", kokoro.outerSha256)

        val vits = ModelReleaseDescriptor.VITS
        assertEquals("VITS", vits.engine.name)
        assertEquals("homoludens", vits.repositoryOwner)
        assertEquals("citac_knjiga", vits.repositoryName)
        assertEquals("https://github.com/homoludens/citac_knjiga/releases/download/" +
            "vits-model-v1.1.0/serbian-vits-1.1.0.zip", vits.assetUrl)
        assertEquals("vits-model-v1.1.0", vits.releaseTag)
        assertEquals("serbian-vits-1.1.0.zip", vits.assetFileName)
        assertEquals("1.1.0", vits.version)
        assertEquals(41_111_655L, vits.expectedSizeBytes)
        assertEquals("e1522a1fd13b015fdf0617af0c3125cb68ae8babd3dadd88f52c32e0dcae25f2", vits.outerSha256)
    }

    @Test
    public fun descriptorsAreImmutablePinnedInstances() {
        assertSame(ModelReleaseDescriptor.KOKORO, ModelReleaseDescriptor.ALL[0])
        assertSame(ModelReleaseDescriptor.VITS, ModelReleaseDescriptor.ALL[1])
    }

    @Test
    public fun rejectsInvalidHostPathHashSizeAndInconsistentMetadata() {
        val valid = ModelReleaseDescriptor.KOKORO
        listOf<() -> Unit>(
            { valid.copy(assetUrl = valid.assetUrl.replace("github.com", "example.com")) },
            { valid.copy(assetUrl = valid.assetUrl.replace("kokoro-model-v1.0.0", "other-release")) },
            { valid.copy(outerSha256 = valid.outerSha256.uppercase()) },
            { valid.copy(expectedSizeBytes = 0L) },
            { valid.copy(assetFileName = "serbian-vits-1.0.0.zip") },
            { valid.copy(version = "2.0.0") },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }
}
