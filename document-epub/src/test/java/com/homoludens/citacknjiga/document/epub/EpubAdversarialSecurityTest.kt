package com.homoludens.citacknjiga.document.epub

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubAdversarialSecurityTest {
    @Test(timeout = 2_000)
    public fun committedAttackFixturesHaveTypedRejections() {
        val expected = mapOf(
            "attack-zip-slip.epub" to EpubSecurityFailureCode.INVALID_ENTRY_PATH,
            "attack-decompression-bomb.epub" to EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
            "attack-oversized-entry.epub" to EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
            "attack-entry-count.epub" to EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
            "attack-entity-expansion.epub" to EpubSecurityFailureCode.XML_DTD_FORBIDDEN,
            "attack-external-resource.epub" to EpubSecurityFailureCode.EXTERNAL_RESOURCE,
            "attack-encrypted-entry.epub" to EpubSecurityFailureCode.ENCRYPTED_ENTRY,
            "malformed-navigation.epub" to EpubSecurityFailureCode.MALFORMED_XML,
        )

        expected.forEach { (fixture, code) ->
            val result = EpubSecurityValidator().validate(fixtureFile(fixture))
            val rejection = result as EpubSecurityValidation.Rejected

            assertEquals(fixture, code, rejection.diagnostic.code)
        }
    }

    @Test(timeout = 2_000)
    public fun zipSlipPathVariantsFailCanonicalContainment() {
        listOf("../outside.txt", "nested/../../outside.txt", "/outside.txt", "C:\\outside.txt").forEach { name ->
            val result = EpubSecurityValidator().validate(
                archive(name to "must not escape".toByteArray(), stored = true),
            ) as EpubSecurityValidation.Rejected

            assertEquals(name, EpubSecurityFailureCode.INVALID_ENTRY_PATH, result.diagnostic.code)
            assertEquals(name, result.diagnostic.entryName)
        }
    }

    @Test(timeout = 2_000)
    public fun generatedArchiveBoundsRejectSmallControlledAttacks() {
        assertRejected(
            archive("entry.bin" to ByteArray(8 * 1024), stored = true),
            EpubSecurityLimits(maxIndividualEntryBytes = 8 * 1024),
            EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
        )
        assertRejected(
            archive("one.bin" to ByteArray(64), "two.bin" to ByteArray(64), stored = true),
            EpubSecurityLimits(maxTotalUncompressedBytes = 128),
            EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED,
        )
        assertRejected(
            archive(
                "one.bin" to byteArrayOf(1),
                "two.bin" to byteArrayOf(2),
                "three.bin" to byteArrayOf(3),
                "four.bin" to byteArrayOf(4),
                stored = true,
            ),
            EpubSecurityLimits(maxEntries = 4),
            EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
        )
        assertRejected(
            archive("bomb.bin" to ByteArray(32 * 1024) { 'A'.code.toByte() }),
            EpubSecurityLimits(
                maxIndividualEntryBytes = 64 * 1024,
                maxTotalUncompressedBytes = 64 * 1024,
                maxCompressionRatio = 10.0,
            ),
            EpubSecurityFailureCode.COMPRESSION_RATIO_EXCEEDED,
        )
    }

    @Test(timeout = 2_000)
    public fun dtdEntitiesAndExternalXmlReferencesAreRejectedWithoutResolution() {
        val dtd = """
            <?xml version="1.0"?>
            <!DOCTYPE html [<!ENTITY expansion "bounded marker">]>
            <html><body><p>&expansion;</p></body></html>
        """.trimIndent().toByteArray()
        val externalDtd = """
            <?xml version="1.0"?>
            <!DOCTYPE html SYSTEM "file:///etc/passwd">
            <html><body><p>no external file</p></body></html>
        """.trimIndent().toByteArray()
        val externalResource = """
            <?xml version="1.0"?>
            <html><body><img src="file:///etc/passwd"/><a href="https://example.invalid/book">local text</a></body></html>
        """.trimIndent().toByteArray()

        assertRejected(
            archive("entity.xhtml" to dtd),
            EpubSecurityLimits(maxIndividualEntryBytes = 16 * 1024),
            EpubSecurityFailureCode.XML_DTD_FORBIDDEN,
        )
        assertRejected(
            archive("external-dtd.xhtml" to externalDtd),
            EpubSecurityLimits(maxIndividualEntryBytes = 16 * 1024),
            EpubSecurityFailureCode.XML_DTD_FORBIDDEN,
        )
        assertRejected(
            archive("external.xhtml" to externalResource),
            EpubSecurityLimits(maxIndividualEntryBytes = 16 * 1024),
            EpubSecurityFailureCode.EXTERNAL_RESOURCE,
        )
    }

    private fun assertRejected(
        archive: File,
        limits: EpubSecurityLimits,
        expectedCode: EpubSecurityFailureCode,
    ) {
        val result = EpubSecurityValidator(limits).validate(archive) as EpubSecurityValidation.Rejected

        assertEquals(expectedCode, result.diagnostic.code)
        assertTrue(archive.exists())
    }

    private fun fixtureFile(name: String): File =
        checkNotNull(javaClass.getResource("/fixtures/$name")).toURI().let(::File)

    private fun archive(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): File {
        val file = createTempDirectory().toFile().resolve("adversarial.epub")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }
}
