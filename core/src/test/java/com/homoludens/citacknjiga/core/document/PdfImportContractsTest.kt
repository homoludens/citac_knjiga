package com.homoludens.citacknjiga.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class PdfImportContractsTest {
    private val fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    public fun locatorsRenderCanonicallyAndSerializationKeepsOrder() {
        val page = PageLocator(fingerprint, 3)
        assertEquals("pdf:sha256=$fingerprint/page/3", page.toString())
        assertEquals("pdf:sha256=$fingerprint/page/3/block/2", page.block(2))
        val ir = DocumentIr(
            "Naslov", "Autor", "sr",
            listOf(DocumentChapter(0, "Page 3", page, listOf(DocumentBlock(0, com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH, "tekst", page.block(0))))),
            ImportProvenance(fingerprint, "content://redacted", "/private/source.pdf"),
        )
        assertTrue(ir.canonicalSerialization().contains("chapter=0|Page 3|$page"))
        assertFalse(ir.canonicalSerialization().contains("content://"))
    }

    @Test
    public fun productionLimitsAcceptEqualityAndRejectFirstValueBeyondEachBound() {
        val limits = PdfImportLimits.Production
        assertTrue(limits.acceptsSourceBytes(536_870_912))
        assertFalse(limits.acceptsSourceBytes(536_870_913))
        assertTrue(limits.acceptsPageCount(10_000))
        assertFalse(limits.acceptsPageCount(10_001))
        assertTrue(limits.acceptsSelectedPages(200))
        assertFalse(limits.acceptsSelectedPages(201))
        assertTrue(limits.acceptsPageTextBytes(1_048_576))
        assertFalse(limits.acceptsPageTextBytes(1_048_577))
        assertTrue(limits.acceptsRangeTextBytes(33_554_432))
        assertFalse(limits.acceptsRangeTextBytes(33_554_433))
    }
}
