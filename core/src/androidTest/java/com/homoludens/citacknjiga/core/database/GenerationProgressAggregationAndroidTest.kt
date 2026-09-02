package com.homoludens.citacknjiga.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

public class GenerationProgressAggregationAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String

    @Before
    public fun setUp() {
        databaseName = "generation-progress-${UUID.randomUUID()}.db"
        database = AudiobookDatabase.create(context, databaseName)
        val dao = database.audiobookDao()
        dao.insertProject(project("book"))
        dao.insertProject(project("legacy-book"))
        listOf(
            chapter("partial", "book", 0),
            chapter("complete", "book", 1),
            chapter("failed", "book", 2),
            chapter("cancelled", "book", 3),
            chapter("legacy", "legacy-book", 0),
        ).forEach(dao::insertChapter)
        listOf(
            run("run-partial", "book", GenerationRunStatus.RUNNING, 10),
            run("run-complete", "book", GenerationRunStatus.COMPLETED, 20),
            run("run-failed", "book", GenerationRunStatus.FAILED, 30),
            run("run-cancelled", "book", GenerationRunStatus.CANCELLED, 40),
        ).forEach(dao::insertGenerationRun)
        listOf(
            segment("partial-ready", "partial", "run-partial", AudioSegmentStatus.READY, 30),
            segment("partial-pending", "partial", "run-partial", AudioSegmentStatus.PENDING, 70),
            segment("partial-failed", "partial", "run-partial", AudioSegmentStatus.FAILED, 50),
            segment("partial-stale", "partial", "run-partial", AudioSegmentStatus.STALE, 10),
            segment("complete-ready", "complete", "run-complete", AudioSegmentStatus.READY, 45),
            segment("failed-ready", "failed", "run-failed", AudioSegmentStatus.READY, 20),
            segment("failed-failed", "failed", "run-failed", AudioSegmentStatus.FAILED, 80),
            segment("cancelled-pending", "cancelled", "run-cancelled", AudioSegmentStatus.PENDING, 60),
            segment("legacy-ready", "legacy", null, AudioSegmentStatus.READY, null),
            segment("legacy-pending", "legacy", null, AudioSegmentStatus.PENDING, null),
        ).also { segments ->
            segments.groupBy { it.chapterId }.values.forEach { chapterSegments ->
                chapterSegments.forEachIndexed { ordinal, segment ->
                    dao.insertNarrationBlock(block(segment.chapterId, segment.id, ordinal))
                }
            }
            segments.forEach(dao::insertAudioSegment)
        }
    }

    @After
    public fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    public fun aggregatesWordsOnlyForReadySegmentsAndSurvivesReopen(): Unit = runBlocking {
        assertProgress(database.audiobookDao().observeChapterGenerationProgress().first())
        assertEquals(
            GenerationProgressSnapshot(
                scopeId = "book",
                completedWords = 95,
                totalWords = 365,
                completedSegments = 3,
                totalSegments = 8,
                estimatedSegments = 8,
                generationStatus = GenerationRunStatus.CANCELLED,
            ),
            database.audiobookDao().observeBookGenerationProgress().first().single { it.scopeId == "book" },
        )

        database.close()
        database = AudiobookDatabase.create(context, databaseName)

        assertProgress(database.audiobookDao().observeChapterGenerationProgress().first())
        assertEquals(95L, database.audiobookDao().observeBookGenerationProgress().first()
            .single { it.scopeId == "book" }.completedWords)
    }

    private fun assertProgress(rows: List<GenerationProgressSnapshot>) {
        assertEquals(
            GenerationProgressSnapshot(
                scopeId = "partial",
                completedWords = 30,
                totalWords = 160,
                completedSegments = 1,
                totalSegments = 4,
                estimatedSegments = 4,
                generationStatus = GenerationRunStatus.RUNNING,
            ),
            rows.single { it.scopeId == "partial" },
        )
        assertEquals(45L, rows.single { it.scopeId == "complete" }.completedWords)
        assertEquals(45L, rows.single { it.scopeId == "complete" }.totalWords)
        assertEquals(100, rows.single { it.scopeId == "complete" }.percentage)
        assertEquals(GenerationRunStatus.COMPLETED, rows.single { it.scopeId == "complete" }.generationStatus)
        assertEquals(20L, rows.single { it.scopeId == "failed" }.completedWords)
        assertEquals(100L, rows.single { it.scopeId == "failed" }.totalWords)
        assertEquals(20, rows.single { it.scopeId == "failed" }.percentage)
        assertEquals(GenerationRunStatus.FAILED, rows.single { it.scopeId == "failed" }.generationStatus)
        assertEquals(0L, rows.single { it.scopeId == "cancelled" }.completedWords)
        assertEquals(60L, rows.single { it.scopeId == "cancelled" }.totalWords)
        assertEquals(0, rows.single { it.scopeId == "cancelled" }.percentage)
        assertEquals(GenerationRunStatus.CANCELLED, rows.single { it.scopeId == "cancelled" }.generationStatus)
        assertEquals(
            GenerationProgressSnapshot(
                scopeId = "legacy",
                completedWords = 0,
                totalWords = 0,
                completedSegments = 1,
                totalSegments = 2,
                estimatedSegments = 0,
                generationStatus = null,
            ),
            rows.single { it.scopeId == "legacy" },
        )
    }

    private fun project(id: String) = BookProjectEntity(
        id = id,
        title = id,
        sourceUri = "content://$id",
        sourceFingerprint = id,
        status = BookProjectStatus.GENERATING,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(id: String, projectId: String, ordinal: Int) = ChapterEntity(
        id = id,
        bookProjectId = projectId,
        ordinal = ordinal,
        title = id,
        status = ChapterStatus.GENERATING,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun block(chapterId: String, segmentId: String, ordinal: Int) = NarrationBlockEntity(
        id = "block-$segmentId",
        chapterId = chapterId,
        ordinal = ordinal,
        blockType = NarrationBlockType.PARAGRAPH,
        sourceText = "Text",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun run(id: String, projectId: String, status: GenerationRunStatus, requestedAt: Long) =
        GenerationRunEntity(
            id = id,
            bookProjectId = projectId,
            preprocessingVersion = "prep",
            pronunciationVersion = "pron",
            inferenceSettingsHash = "settings",
            audioProcessingVersion = "audio",
            status = status,
            requestedAt = requestedAt,
        )

    private fun segment(
        id: String,
        chapterId: String,
        runId: String?,
        status: AudioSegmentStatus,
        estimatedWords: Int?,
    ) = AudioSegmentEntity(
        id = id,
        chapterId = chapterId,
        narrationBlockId = "block-$id",
        sequence = id.hashCode(),
        chunkOrdinal = 0,
        generationRunId = runId,
        estimatedWordCount = estimatedWords,
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )
}
