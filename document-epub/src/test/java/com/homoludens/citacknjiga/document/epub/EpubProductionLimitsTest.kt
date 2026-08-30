package com.homoludens.citacknjiga.document.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubProductionLimitsTest {
    @Test
    public fun productionProfileIsFixedToTheInclusiveCapabilityValues() {
        assertEquals(536_870_912L, EpubProductionLimits.MAX_SOURCE_BYTES)
        assertEquals(4_096, EpubProductionLimits.MAX_ENTRY_COUNT)
        assertEquals(1_073_741_824L, EpubProductionLimits.MAX_TOTAL_UNCOMPRESSED_BYTES)
        assertEquals(134_217_728L, EpubProductionLimits.MAX_ENTRY_UNCOMPRESSED_BYTES)
        assertEquals(8_388_608L, EpubProductionLimits.MAX_XML_TEXT_BYTES)
        assertEquals(33_554_432L, EpubProductionLimits.MAX_XML_TEXT_TOTAL_BYTES)
        assertEquals(33_554_432L, EpubProductionLimits.MAX_COVER_BYTES)
        assertEquals(64, EpubProductionLimits.MAX_XML_NESTING_DEPTH)
        assertEquals(250L, EpubProductionLimits.MAX_ENTRY_RATIO_NUMERATOR)
        assertEquals(100L, EpubProductionLimits.MAX_ARCHIVE_RATIO_NUMERATOR)
        assertEquals(1_048_576L, EpubProductionLimits.INDIVIDUAL_RATIO_THRESHOLD_BYTES)
    }

    @Test
    public fun lexicalResolverNormalizesReferencesWithoutFilesystemAccess() {
        assertEquals("OEBPS/text/chapter.xhtml", ArchivePathResolver.normalizeEntry("OEBPS/./text/chapter.xhtml"))
        assertEquals("OEBPS/text/chapter.xhtml", ArchivePathResolver.resolve("OEBPS/opf/package.opf", "../text/chapter.xhtml?x=1#top").getOrNull())
        assertNull(ArchivePathResolver.normalizeEntry("OEBPS/../../outside"))
        assertTrue(ArchivePathResolver.resolve("OEBPS/chapter.xhtml", "%2e%2e/%2e%2e/outside").isFailure)
        assertTrue(ArchivePathResolver.resolve("OEBPS/chapter.xhtml", "https://example.invalid/book").isFailure)
    }

    @Test
    public fun countersAndRatiosAcceptBoundariesAndRejectOnlyTheFirstByteBeyond() {
        val counter = EpubCheckedCounter(10)
        assertTrue(counter.add(10))
        assertEquals(10L, counter.observed)
        assertTrue(!counter.add(1))
        assertEquals(11L, counter.observed)

        assertTrue(!EpubProductionLimits.ratioExceeded(250, 1, 250))
        assertTrue(EpubProductionLimits.ratioExceeded(251, 1, 250))
        assertTrue(!EpubProductionLimits.ratioExceeded(0, 0, 100))
        assertTrue(EpubProductionLimits.ratioExceeded(1, 0, 100))
        assertTrue(!EpubProductionLimits.ratioExceeded(250 * 1024, 1024, 250))
    }
}
