package com.homoludens.citacknjiga.document.epub

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs committed EPUB fixtures through the SAF copy, security, and private-source boundaries. */
public class EpubFixtureImportSecurityAndroidTest {
    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    public fun validEpubFixturesImportToPrivateSourcesInDeclaredSpineOrder() {
        listOf("serbian-epub2.epub", "serbian-epub3.epub").forEach { fixture ->
            val root = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "epub-fixture-${UUID.randomUUID()}",
            )
            val storage = AppPrivateStorage(root)
            try {
                val imported = importFixture(storage, fixture)
                assertTrue(imported.sourceFile.canonicalPath.startsWith(root.canonicalPath + File.separator))
                assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })

                val parsed = EpubDocumentParser(storage).parse(imported)
                val document = (parsed as EpubParseResult.Parsed).document
                assertEquals(2, document.chapters.size)
                assertEquals(
                    if (fixture == "serbian-epub2.epub") {
                        listOf("Други лист", "Први лист")
                    } else {
                        listOf("Поглавље Б", "Поглавље А")
                    },
                    document.chapters.map { it.title },
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    public fun hostileFixturesAreRejectedBeforePrivatePublication() {
        val expected = mapOf(
            "attack-zip-slip.epub" to EpubSecurityFailureCode.INVALID_ENTRY_PATH,
            "attack-decompression-bomb.epub" to EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
            "attack-oversized-entry.epub" to EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
            "attack-entry-count.epub" to EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
            "attack-entity-expansion.epub" to EpubSecurityFailureCode.XML_DTD_FORBIDDEN,
            "attack-external-resource.epub" to EpubSecurityFailureCode.EXTERNAL_RESOURCE,
            "attack-encrypted-entry.epub" to EpubSecurityFailureCode.ENCRYPTED_ENTRY,
            "malformed-content.epub" to EpubSecurityFailureCode.MALFORMED_XML,
            "malformed-navigation.epub" to EpubSecurityFailureCode.MALFORMED_XML,
        )
        val root = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "epub-security-${UUID.randomUUID()}",
        )
        val outside = File(root.parentFile, "${root.name}-outside.txt").apply { writeText("unchanged") }
        try {
            expected.entries.forEachIndexed { index, (fixture, code) ->
                val storage = AppPrivateStorage(root)
                val result = SafEpubSourceRepository(
                    sourceReader = EpubSourceReader { testContext.assets.open(fixture) },
                    storage = storage,
                    artifactStore = AtomicArtifactStore(storage),
                    projectIndex = RecordingProjectIndex(),
                    projectIdFactory = { "security-$index" },
                ).importSelected(Uri.parse("content://fixtures/$fixture"))

                val failure = result as EpubImportResult.Failed
                assertEquals(fixture, EpubImportError.SECURITY_VALIDATION_FAILED, failure.error)
                assertEquals(fixture, code, failure.securityDiagnostic?.code)
                assertFalse(storage.sourceDocumentsDirectory.walkTopDown().any { it.isFile })
                assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
            }
            assertEquals("unchanged", outside.readText())
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    private fun importFixture(storage: AppPrivateStorage, fixture: String): ImportedEpubSource {
        val result = SafEpubSourceRepository(
            sourceReader = EpubSourceReader { testContext.assets.open(fixture) },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = RecordingProjectIndex(),
            projectIdFactory = { "valid-${fixture.substringBeforeLast('.') }" },
        ).importSelected(Uri.parse("content://fixtures/$fixture"))
        return (result as EpubImportResult.Imported).source
    }

    private class RecordingProjectIndex : EpubProjectIndex {
        override fun findByFingerprint(fingerprint: String): ExistingEpubProject? = null

        override fun recordImportedSource(source: ImportedEpubSource) = Unit
    }
}
