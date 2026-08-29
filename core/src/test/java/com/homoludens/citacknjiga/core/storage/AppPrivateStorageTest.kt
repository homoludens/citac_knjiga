package com.homoludens.citacknjiga.core.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class AppPrivateStorageTest {
    @Test
    public fun fixedAreasHaveStableNames() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())

        assertEquals("sources", storage.sourceDocumentsDirectory.name)
        assertEquals("model-packages", storage.modelPackagesDirectory.name)
        assertEquals("canonical-text", storage.canonicalTextDirectory.name)
        assertEquals("covers", storage.coversDirectory.name)
        assertEquals("temporary", storage.temporaryDirectory.name)
        assertEquals("ready-audio", storage.readyAudioDirectory.name)
        assertEquals("diagnostics", storage.diagnosticsDirectory.name)
        assertEquals("typed-proof", storage.typedProofDirectory.name)
        assertEquals("benchmark-reports", storage.benchmarkReportsDirectory.name)
        assertEquals("parity-input", storage.parityInputDirectory.name)
        assertEquals("parity-reports", storage.parityReportsDirectory.name)
    }

    @Test
    public fun representativePathsKeepTheirOwnersAndModelCompatibility() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)

        assertEquals(File(root, "sources/book-1/source.epub"), storage.sourceDocument("book-1"))
        assertEquals(
            File(root, "canonical-text/book-1/chapter-2.md"),
            storage.canonicalChapterText("book-1", "chapter-2"),
        )
        assertEquals(File(root, "covers/book-1/cover"), storage.coverImage("book-1"))
        assertEquals(File(root, "model-packages/active.zip"), storage.activeModelPackage)
        assertEquals(
            File(root, "temporary/import-1/copy.epub"),
            storage.temporaryFile("import-1", "copy.epub"),
        )
        assertEquals(
            File(root, "ready-audio/book-1/chapter-2/segment-3.m4a"),
            storage.readySegmentAudio("book-1", "chapter-2", "segment-3"),
        )
        assertEquals(
            File(root, "diagnostics/import.json"),
            storage.diagnosticFile("import.json"),
        )
    }

    @Test
    public fun unsafeComponentsAreRejectedBeforeTheyCanEscapeRoot() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())

        listOf("../outside", "book/child", "book\\child", "", ".", "..", "book\u0000id").forEach { id ->
            val failure = runCatching { storage.sourceDocument(id) }.exceptionOrNull()
            assertTrue("Expected unsafe component to be rejected: $id", failure is IllegalArgumentException)
        }
    }
}
