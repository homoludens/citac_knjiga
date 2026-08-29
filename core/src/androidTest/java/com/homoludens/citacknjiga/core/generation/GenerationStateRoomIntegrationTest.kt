package com.homoludens.citacknjiga.core.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

public class GenerationStateRoomIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String

    @Before
    public fun setUp() {
        databaseName = "generation-state-${UUID.randomUUID()}.db"
        database = AudiobookDatabase.create(context, databaseName)
        val dao = database.audiobookDao()
        dao.insertProject(project())
        dao.insertChapter(chapter())
        dao.insertNarrationBlock(
            com.homoludens.citacknjiga.core.database.NarrationBlockEntity(
                id = "block",
                chapterId = "chapter",
                ordinal = 0,
                blockType = com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH,
                sourceText = "Tekst.",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.insertModelPackage(model())
        dao.insertGenerationRun(run())
        dao.insertAudioSegment(segment())
    }

    @After
    public fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    public fun queuedRunClaimsOnlyTheNextPendingSegmentInOrder() {
        val dao = database.audiobookDao()
        dao.insertNarrationBlock(
            com.homoludens.citacknjiga.core.database.NarrationBlockEntity(
                id = "block-2",
                chapterId = "chapter",
                ordinal = 1,
                blockType = com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH,
                sourceText = "Други текст.",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.insertAudioSegment(segment().copy(id = "segment-2", narrationBlockId = "block-2", sequence = 1))
        val service = GenerationStateService(database) { 10L }

        service.startGenerationRun("run")
        val first = service.claimNextSegment("run")
        assertNotNull(first)
        assertEquals("segment", first!!.segment.id)
        assertEquals(AudioSegmentStatus.GENERATING, dao.findAudioSegmentById("segment")!!.status)
        service.transitionAudioSegment("segment", AudioSegmentStatus.READY)

        val second = service.claimNextSegment("run")
        assertNotNull(second)
        assertEquals("segment-2", second!!.segment.id)
        assertNull(service.claimNextSegment("run"))
    }

    @Test
    public fun transitionsPersistRetriesErrorsAndTerminalStatesAfterReopen() {
        val service = GenerationStateService(database) { 10L }
        service.transitionProject("book", BookProjectStatus.GENERATING)
        service.transitionChapter("chapter", ChapterStatus.GENERATING)

        val firstRun = service.transitionGenerationRun("run", GenerationRunStatus.RUNNING)
        assertEquals(1, firstRun.attemptCount)
        val failedRun = service.transitionGenerationRun(
            "run",
            GenerationRunStatus.FAILED,
            GenerationError("RUNTIME_UNAVAILABLE", "model session closed"),
        )
        assertEquals("RUNTIME_UNAVAILABLE: model session closed", failedRun.lastError)
        val queuedRun = service.retryGenerationRun("run")
        assertEquals(1, queuedRun.attemptCount)
        val secondRun = service.transitionGenerationRun("run", GenerationRunStatus.RUNNING)
        assertEquals(2, secondRun.attemptCount)

        val firstAttempt = service.transitionAudioSegment("segment", AudioSegmentStatus.GENERATING)
        assertEquals(1, firstAttempt.attemptCount)
        val failedSegment = service.transitionAudioSegment(
            "segment",
            AudioSegmentStatus.FAILED,
            GenerationError("AUDIO_INVALID", "generated audio is silent; retry the segment"),
        )
        assertEquals("AUDIO_INVALID: generated audio is silent; retry the segment", failedSegment.lastError)
        val pendingSegment = service.retryAudioSegment("segment")
        assertEquals(1, pendingSegment.attemptCount)
        assertEquals(failedSegment.lastError, pendingSegment.lastError)
        val readySegment = service.transitionAudioSegment("segment", AudioSegmentStatus.GENERATING)
        assertEquals(2, readySegment.attemptCount)
        val completedSegment = service.transitionAudioSegment("segment", AudioSegmentStatus.READY)
        assertEquals(null, completedSegment.lastError)

        service.transitionChapter("chapter", ChapterStatus.READY)
        val completedRun = service.transitionGenerationRun("run", GenerationRunStatus.COMPLETED)
        assertEquals(10L, completedRun.finishedAt)
        service.transitionProject("book", BookProjectStatus.COMPLETED)

        database.close()
        database = AudiobookDatabase.create(context, databaseName)
        val dao = database.audiobookDao()
        assertEquals(BookProjectStatus.COMPLETED, dao.findProjectById("book")!!.status)
        assertEquals(ChapterStatus.READY, dao.findChapterById("chapter")!!.status)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("segment")!!.status)
        assertEquals(2, dao.findAudioSegmentById("segment")!!.attemptCount)
        assertEquals(GenerationRunStatus.COMPLETED, dao.findGenerationRunById("run")!!.status)
        assertThrows(IllegalStateException::class.java) {
            GenerationStateService(database) { 11L }
                .transitionGenerationRun("run", GenerationRunStatus.QUEUED)
        }
    }

    private fun project() = BookProjectEntity(
        id = "book",
        title = "Book",
        sourceUri = "content://book",
        sourceFingerprint = "fingerprint-${UUID.randomUUID()}",
        status = BookProjectStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter() = ChapterEntity(
        id = "chapter",
        bookProjectId = "book",
        ordinal = 0,
        title = "Chapter",
        status = ChapterStatus.PENDING,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun model() = ModelPackageEntity(
        id = "model",
        packageIdentity = "model@1",
        packageVersion = "1",
        packageSha256 = "package",
        modelSha256 = "model-sha",
        voiceSha256 = "voice",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        packagePath = "model.zip",
        status = ModelPackageStatus.ACTIVE,
        importedAt = 1,
    )

    private fun run() = GenerationRunEntity(
        id = "run",
        bookProjectId = "book",
        modelPackageId = "model",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = "settings",
        audioProcessingVersion = "audio-v1",
        requestedAt = 1,
    )

    private fun segment() = AudioSegmentEntity(
        id = "segment",
        chapterId = "chapter",
        narrationBlockId = "block",
        sequence = 0,
        chunkOrdinal = 0,
        generationRunId = "run",
        modelPackageId = "model",
        createdAt = 1,
        updatedAt = 1,
    )
}
