package com.homoludens.citacknjiga.playback.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

public class ExportFileNamingTest {
    @Test
    public fun namesUseOneBasedZeroPaddedOrderAndSanitizedMetadata() {
        assertEquals(
            "0001-Citanje_knjige.wav",
            ExportFileNaming.chapterFileName(0, "Čitanje / knjige", ".WAV"),
        )
    }

    @Test
    public fun collisionsAreDeterministicAndCaseInsensitive() {
        val first = ExportFileNaming.collisionSafeName("0001-001-title.m4a", listOf("0001-001-title.m4a"))
        val second = ExportFileNaming.collisionSafeName(
            "0001-001-title.m4a",
            listOf("0001-001-title.m4a", "0001-001-title-2.m4a"),
        )

        assertEquals("0001-001-title-2.m4a", first)
        assertEquals("0001-001-title-3.m4a", second)
        assertNotEquals(first.lowercase(), "0001-001-title.m4a")
    }
}
