package com.homoludens.citacknjiga.diagnostics

import com.homoludens.citacknjiga.document.epub.EpubSecurityDiagnostic
import com.homoludens.citacknjiga.document.epub.EpubSecurityFailureCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubImportDiagnosticFormatterTest {
    @Test
    public fun formatsXmlLimitAndCompatibilityWarningWithoutLeakingInput() {
        val xml = EpubSecurityDiagnostic(
            code = EpubSecurityFailureCode.XML_SIZE_EXCEEDED,
            entryName = "OEBPS/chapter.xhtml",
            observed = 8_388_609,
            limit = 8_388_608,
            observedUnit = "bytes",
        )
        val hyperlink = EpubSecurityDiagnostic(
            code = EpubSecurityFailureCode.EXTERNAL_RESOURCE,
            entryName = "OEBPS/chapter.xhtml",
            rule = "compat.external-hyperlink",
            scheme = "https",
        )

        val xmlMessage = EpubImportDiagnosticFormatter.formatEnglish(xml)
        val warning = EpubImportDiagnosticFormatter.formatSerbian(hyperlink)

        assertTrue(xmlMessage.contains("8388609"))
        assertTrue(xmlMessage.contains("8388608"))
        assertTrue(xmlMessage.contains("OEBPS/chapter.xhtml"))
        assertTrue(warning.contains("sačuvana"))
        assertFalse(warning.contains("example.invalid"))
    }
}
