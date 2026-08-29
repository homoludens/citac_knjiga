package com.homoludens.citacknjiga.document.epub

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubSourceRepositoryTest {
    @Test
    public fun importedSourceContainsContentFingerprint() {
        val bytes = fixtureBytes("serbian-epub3.epub")
        val index = FakeProjectIndex()
        val repository = repository(bytes, index)

        val result = repository.importSource("content://books/one")

        val imported = (result as EpubImportResult.Imported).source
        assertEquals("e087d2f90a707bbe50693a422b2b61c0bb41e7110f5e51f65bbdffac8947cd12", imported.fingerprint)
        assertEquals(bytes.toList(), imported.sourceFile.readBytes().toList())
        assertEquals(imported, index.sources.single())
    }

    @Test
    public fun duplicateFingerprintDoesNotReplaceExistingSource() {
        val bytes = fixtureBytes("serbian-epub3.epub")
        val index = FakeProjectIndex()
        var id = 0
        val repository = repository(bytes, index, projectIdFactory = { "project-${++id}" })

        val first = (repository.importSource("content://books/one") as EpubImportResult.Imported).source
        val second = repository.importSource("content://books/two")

        val duplicate = (second as EpubImportResult.Duplicate).existingProject
        assertEquals(first.projectId, duplicate.projectId)
        assertEquals(first.sourceFile, duplicate.sourceFile)
        assertEquals("content://books/one", duplicate.sourceUri)
        assertEquals(bytes.toList(), first.sourceFile.readBytes().toList())
        assertEquals(1, index.sources.size)
    }

    @Test
    public fun failedCopyCleansTemporaryFilesAndDoesNotRecordProject() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val index = FakeProjectIndex()
        val repository = SafEpubSourceRepository(
            sourceReader = EpubSourceReader { FailingInputStream() },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = index,
            projectIdFactory = { "copy-failure" },
        )

        val result = repository.importSource("content://books/broken")

        assertEquals(EpubImportError.COPY_FAILED, (result as EpubImportResult.Failed).error)
        assertTrue(index.sources.isEmpty())
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
        assertFalse(storage.sourceDocumentsDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    public fun failedPublicationCleansPrivateStagingAndPreservesExistingPath() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        check(storage.sourceDocumentsDirectory.parentFile?.isDirectory == true)
        storage.sourceDocumentsDirectory.writeText("not a directory")
        val index = FakeProjectIndex()
        val repository = repository(
            bytes = fixtureBytes("serbian-epub3.epub"),
            index = index,
            projectIdFactory = { "publication-failure" },
            storage = storage,
        )

        val result = repository.importSource("content://books/one")

        assertEquals(EpubImportError.PUBLICATION_FAILED, (result as EpubImportResult.Failed).error)
        assertEquals("not a directory", storage.sourceDocumentsDirectory.readText())
        assertTrue(index.sources.isEmpty())
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    public fun securityRejectionCleansStagingAndPreservesOutsideMarker() {
        val root = createTempDirectory().toFile()
        val outside = File(root.parentFile, "${root.name}-outside.txt").apply { writeText("unchanged") }
        val index = FakeProjectIndex()
        val attacks = listOf(
            "attack-zip-slip.epub",
            "attack-decompression-bomb.epub",
            "attack-oversized-entry.epub",
            "attack-entry-count.epub",
            "attack-entity-expansion.epub",
            "attack-external-resource.epub",
            "attack-encrypted-entry.epub",
        )
        var projectNumber = 0
        attacks.forEach { fixture ->
            val repository = repository(
                bytes = fixtureBytes(fixture),
                index = index,
                storage = AppPrivateStorage(root),
                projectIdFactory = { "attack-${++projectNumber}" },
            )

            val failure = repository.importSource("content://books/$fixture") as EpubImportResult.Failed

            assertEquals(EpubImportError.SECURITY_VALIDATION_FAILED, failure.error)
        }
        assertEquals("unchanged", outside.readText())
        assertTrue(index.sources.isEmpty())
        assertFalse(root.walkTopDown().any { it.isFile })
        assertTrue(outside.delete())
    }

    @Test
    public fun publishedSourceAndTemporaryCopyStayWithinPrivateRoot() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val repository = repository(fixtureBytes("serbian-epub3.epub"), FakeProjectIndex(), storage = storage)

        val source = (repository.importSource("content://books/one") as EpubImportResult.Imported).source

        assertTrue(source.sourceFile.canonicalFile.toPath().startsWith(storage.rootDirectory.toPath()))
        assertTrue(source.sourceFile.canonicalFile.toPath().startsWith(storage.sourceDocumentsDirectory.toPath()))
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    private fun repository(
        bytes: ByteArray,
        index: FakeProjectIndex,
        projectIdFactory: () -> String = { "project-1" },
        storage: AppPrivateStorage = AppPrivateStorage(createTempDirectory().toFile()),
    ): SafEpubSourceRepository = SafEpubSourceRepository(
        sourceReader = EpubSourceReader { bytes.inputStream() },
        storage = storage,
        artifactStore = AtomicArtifactStore(storage),
        projectIndex = index,
        projectIdFactory = projectIdFactory,
    )

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use { it.readBytes() }

    private class FakeProjectIndex : EpubProjectIndex {
        val sources = mutableListOf<ImportedEpubSource>()

        override fun findByFingerprint(fingerprint: String): ExistingEpubProject? =
            sources.firstOrNull { it.fingerprint == fingerprint }?.let { source ->
                ExistingEpubProject(source.projectId, source.sourceUri, source.fingerprint, source.sourceFile)
            }

        override fun recordImportedSource(source: ImportedEpubSource) {
            sources += source
        }
    }

    private class FailingInputStream : InputStream() {
        private var reads = 0

        override fun read(): Int {
            if (reads++ > 8) throw IOException("source interrupted")
            return 'x'.code
        }
    }
}
