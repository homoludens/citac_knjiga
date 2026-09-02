package com.homoludens.citacknjiga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class PdfImportUiTest {
    @Test
    public fun invalidPdfRangesAreRejectedBeforePreview() {
        assertEquals(2, parsePdfPageRange("2", "3", 5)?.startPage)
        assertNull(parsePdfPageRange("0", "3", 5))
        assertNull(parsePdfPageRange("4", "2", 5))
        assertNull(parsePdfPageRange("1", "6", 5))
        assertNull(parsePdfPageRange("one", "2", 5))
    }
}
