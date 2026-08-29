package com.homoludens.citacknjiga.document.epub

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubImportPreviewTest {
    @Test
    public fun previewContainsMetadataSpineNarrationWarningsAndEstimateWithoutPublication() {
        val setup = setup()

        val result = setup.service.previewSource("content://books/one")

        val preview = (result as EpubPreviewResult.Ready).preview
        assertEquals("Мала књига за проверу", preview.document.metadata.title)
        assertEquals(listOf("Поглавље Б", "Поглавље А"), preview.canonical.chapters.map { it.title })
        assertTrue(preview.canonical.chapters.first().narrationText.isNotBlank())
        assertTrue(preview.canonical.warnings.isEmpty())
        assertTrue(preview.storage.requiredBytes > preview.storage.sourceBytes)
        assertTrue(preview.stagedSource.sourceFile.isFile)
        assertFalse(setup.storage.sourceDocument(preview.stagedSource.projectId).exists())
        assertFalse(setup.storage.canonicalChapterText(preview.stagedSource.projectId, "epub3-chapter-0").exists())
        assertTrue(setup.index.sources.isEmpty())
    }

    @Test
    public fun acceptancePublishesThePreviouslyPreviewedSourceAndCanonicalOutput() {
        val setup = setup()
        val preview = ((setup.service.previewSource("content://books/one")) as EpubPreviewResult.Ready).preview

        val result = setup.service.accept(preview)

        val published = result as EpubAcceptanceResult.Published
        assertEquals(preview.stagedSource.projectId, published.source.projectId)
        assertTrue(published.source.sourceFile.isFile)
        assertTrue(
            setup.storage.canonicalChapterText(
                published.source.projectId,
                preview.canonical.chapters.first().chapterId,
            ).isFile,
        )
        assertTrue(setup.storage.importWarnings(published.source.projectId).isFile)
        assertEquals(1, setup.index.sources.size)
        assertFalse(preview.stagedSource.sourceFile.exists())
    }

    @Test
    public fun previewSurfacesRecoveredContentWarningsBeforeAcceptance() {
        val setup = setup("serbian-epub2.epub", "warning-project")

        val result = setup.service.previewSource("content://books/warnings")

        val preview = (result as EpubPreviewResult.Ready).preview
        assertTrue(preview.canonical.warnings.any { it.code == EpubImportWarningCode.UNSUPPORTED_CONTENT })
        assertTrue(setup.index.sources.isEmpty())
        assertFalse(setup.storage.sourceDocument("warning-project").exists())
    }

    private fun setup(fixture: String = "serbian-epub3.epub", projectId: String = "preview-project"): Setup {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val index = FakeProjectIndex()
        val repository = SafEpubSourceRepository(
            sourceReader = EpubSourceReader { fixtureBytes(fixture).inputStream() },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = index,
            projectIdFactory = { projectId },
        )
        return Setup(
            storage = storage,
            index = index,
            service = EpubImportPreviewService(
                sourceRepository = repository,
                parser = EpubDocumentParser(storage),
                canonicalText = EpubCanonicalTextService(storage, AtomicArtifactStore(storage)),
            ),
        )
    }

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use { it.readBytes() }

    private data class Setup(
        val storage: AppPrivateStorage,
        val index: FakeProjectIndex,
        val service: EpubImportPreviewService,
    )

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
}
