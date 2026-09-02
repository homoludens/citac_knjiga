package com.homoludens.citacknjiga

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.PageLocator
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.document.pdf.NormalizedRect
import com.homoludens.citacknjiga.document.pdf.PdfAcceptanceResult
import com.homoludens.citacknjiga.document.pdf.PdfAcceptanceService
import com.homoludens.citacknjiga.document.pdf.PdfCanonicalTextService
import com.homoludens.citacknjiga.document.pdf.PdfDocumentProjector
import com.homoludens.citacknjiga.document.pdf.PdfImportInspection
import com.homoludens.citacknjiga.document.pdf.PdfImportPreview
import com.homoludens.citacknjiga.document.pdf.PdfSourceReader
import com.homoludens.citacknjiga.document.pdf.PdfStageResult
import com.homoludens.citacknjiga.document.pdf.PdfTextBlock
import com.homoludens.citacknjiga.document.pdf.PageRange
import com.homoludens.citacknjiga.document.pdf.RoomPdfProjectIndex
import com.homoludens.citacknjiga.document.pdf.SafPdfSourceRepository
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class PdfImportPersistenceAndroidTest {
    @Test
    public fun acceptedPdfPersistsThroughRoomProjectIndexWithoutAudio() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("pdf-room-${UUID.randomUUID()}").apply { mkdirs() }
        val projectId = "room-persistence"
        val storage = AppPrivateStorage(root)
        val artifactStore = AtomicArtifactStore(storage)
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader { "%PDF-1.4\nlocal".byteInputStream() },
            storage = storage,
            artifactStore = artifactStore,
            projectIndex = RoomPdfProjectIndex(database.audiobookDao()),
            projectIdFactory = { projectId },
        )
        try {
            val source = (repository.stageSource("content://provider/pdf") as PdfStageResult.Staged).source
            val preview = preview(source)
            val document = PdfDocumentProjector.toIr(preview)
            val result = PdfAcceptanceService(
                repository = repository,
                index = RoomPdfProjectIndex(database.audiobookDao()),
                canonical = PdfCanonicalTextService(storage, artifactStore),
                storage = storage,
                artifactStore = artifactStore,
            ).accept(preview, document)
            val dao = database.audiobookDao()
            val saved = dao.findProjectWithRelations(projectId)

            assertTrue(result is PdfAcceptanceResult.Published)
            assertEquals(BookProjectStatus.READY, saved?.project?.status)
            assertEquals(1, saved?.chapters?.size)
            assertEquals("Преглед текста", saved?.let { dao.findChapterWithRelations(it.chapters.single().id) }
                ?.narrationBlocks?.single()?.sourceText)
            assertTrue(storage.sourcePdf(projectId).isFile)
            assertTrue(dao.findAllGenerationRuns().isEmpty())
            assertTrue(dao.findAllAudioSegments().isEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun preview(source: com.homoludens.citacknjiga.document.pdf.StagedPdfSource): PdfImportPreview {
        val locator = PageLocator(source.fingerprint, 1)
        val block = PdfTextBlock(
            DocumentBlock(0, NarrationBlockType.PARAGRAPH, "Преглед текста", locator.block(0)),
            NormalizedRect(0f, 0f, 1f, 0.1f),
        )
        return PdfImportPreview(
            stagedSource = source,
            inspection = PdfImportInspection(
                pageCount = 1,
                range = PageRange(1, 1),
                pages = listOf(com.homoludens.citacknjiga.document.pdf.PdfPage(1, block.block.sourceText, listOf(block), locator)),
                warnings = emptyList(),
                blockingDiagnostics = emptyList(),
                provenance = ImportProvenance(source.fingerprint, source.sourceUri, source.sourceFile.path, source.projectId),
            ),
        )
    }
}
