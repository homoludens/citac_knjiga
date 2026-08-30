package com.homoludens.citacknjiga.document.epub

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubSecurityValidatorTest {
    @Test
    public fun validTaskFixturesAreAccepted() {
        listOf("serbian-epub2.epub", "serbian-epub3.epub").forEach { fixture ->
            val result = EpubSecurityValidator().validate(fixtureFile(fixture))

            assertEquals(EpubSecurityValidation.Accepted, result)
        }
    }

    @Test
    public fun taskAttackFixturesAreRejectedWithTypedDiagnostics() {
        val expected = mapOf(
            "attack-zip-slip.epub" to EpubSecurityFailureCode.INVALID_ENTRY_PATH,
            "attack-entity-expansion.epub" to EpubSecurityFailureCode.XML_EXTERNAL_ENTITY,
            "attack-external-resource.epub" to EpubSecurityFailureCode.EXTERNAL_RESOURCE,
            "attack-encrypted-entry.epub" to EpubSecurityFailureCode.ENCRYPTED_ENTRY,
        )

        expected.forEach { (fixture, code) ->
            val result = EpubSecurityValidator().validate(fixtureFile(fixture)) as EpubSecurityValidation.Rejected

            assertEquals(fixture, code, result.diagnostic.code)
        }
    }

    @Test
    public fun decompressionRatioIsRejectedBeforeReadingWithASeparateLimit() {
        val result = EpubSecurityValidator(
            EpubSecurityLimits(
                maxIndividualEntryBytes = 200_000,
                maxTotalUncompressedBytes = 500_000,
            ),
        ).validate(fixtureFile("attack-decompression-bomb.epub")) as EpubSecurityValidation.Rejected

        assertEquals(EpubSecurityFailureCode.COMPRESSION_RATIO_EXCEEDED, result.diagnostic.code)
        assertEquals("OEBPS/bomb.txt", result.diagnostic.entryName)
    }

    @Test
    public fun epubDrmMarkerIsRejected() {
        val result = EpubSecurityValidator().validate(
            archive("META-INF/rights.xml" to "<rights/>".toByteArray()),
        ) as EpubSecurityValidation.Rejected

        assertEquals(EpubSecurityFailureCode.DRM_PROTECTED_CONTENT, result.diagnostic.code)
    }

    @Test
    public fun malformedXmlIsRejectedWithoutRecoveryAtTheSecurityBoundary() {
        listOf(
            "malformed-content.epub" to "OEBPS/bad.xhtml",
            "malformed-navigation.epub" to "OEBPS/toc.ncx",
        ).forEach { (fixture, entry) ->
            val result = EpubSecurityValidator().validate(fixtureFile(fixture)) as EpubSecurityValidation.Rejected

            assertEquals(fixture, EpubSecurityFailureCode.MALFORMED_XML, result.diagnostic.code)
            assertEquals(entry, result.diagnostic.entryName)
        }
    }

    @Test
    public fun configuredThresholdsRejectExactBoundaryValues() {
        assertRejected(
            archive("entry.bin" to ByteArray(9)),
            EpubSecurityLimits(maxIndividualEntryBytes = 9),
            EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
        )
        assertRejected(
            archive("one.bin" to ByteArray(5), "two.bin" to ByteArray(5)),
            EpubSecurityLimits(maxTotalUncompressedBytes = 10),
            EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED,
        )
        assertRejected(
            archive("one.bin" to ByteArray(1), "two.bin" to ByteArray(1)),
            EpubSecurityLimits(maxEntries = 2),
            EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
        )
        assertRejected(
            archive("ratio.bin" to byteArrayOf(1, 2, 3, 4), stored = true),
            EpubSecurityLimits(maxCompressionRatio = 1.0),
            EpubSecurityFailureCode.COMPRESSION_RATIO_EXCEEDED,
        )
    }

    @Test
    public fun configuredXmlDepthAndPayloadLimitsAreEnforced() {
        val payload = "<a><b/></a>".toByteArray()
        assertRejected(
            archive("content.xhtml" to payload),
            EpubSecurityLimits(maxXmlNestingDepth = 1),
            EpubSecurityFailureCode.XML_NESTING_EXCEEDED,
        )
        assertRejected(
            archive("content.xhtml" to payload),
            EpubSecurityLimits(maxXmlBytes = payload.size.toLong()),
            EpubSecurityFailureCode.MALFORMED_XML,
        )
        assertEquals(
            EpubSecurityValidation.Accepted,
            EpubSecurityValidator(EpubSecurityLimits(maxXmlNestingDepth = 2)).validate(
                archive("content.xhtml" to payload),
            ),
        )
    }

    @Test
    public fun malformedArchiveIsRejected() {
        val file = createTempDirectory().toFile().resolve("broken.epub").apply { writeText("not a zip") }

        val result = EpubSecurityValidator().validate(file) as EpubSecurityValidation.Rejected

        assertEquals(EpubSecurityFailureCode.MALFORMED_ARCHIVE, result.diagnostic.code)
    }

    private fun assertRejected(
        archive: File,
        limits: EpubSecurityLimits,
        expectedCode: EpubSecurityFailureCode,
    ) {
        val result = EpubSecurityValidator(limits).validate(archive)
        val rejection = result as? EpubSecurityValidation.Rejected
            ?: throw AssertionError("expected $expectedCode, got $result")

        assertEquals(expectedCode, rejection.diagnostic.code)
        assertTrue(archive.exists())
    }

    private fun fixtureFile(name: String): File =
        checkNotNull(javaClass.getResource("/fixtures/$name")).toURI().let(::File)

    private fun archive(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): File {
        val file = createTempDirectory().toFile().resolve("boundary.epub")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) entry.method = ZipEntry.STORED
                if (stored) {
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }
}
