package com.homoludens.citacknjiga.library

import com.homoludens.citacknjiga.AppRoute
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class LibraryStateTest {
    @Test
    public fun mapperCombinesReadinessProgressListeningFailuresAndStorage() {
        val project = project()
        val chapters = listOf(
            chapter("chapter-0", 0, ChapterStatus.GENERATING),
            chapter("chapter-1", 1, ChapterStatus.FAILED, "CHAPTER_FAILED: bad audio"),
        )
        val segments = listOf(
            segment("segment-0", "chapter-0", AudioSegmentStatus.READY, size = 20, duration = 20_000),
            segment("segment-1", "chapter-1", AudioSegmentStatus.FAILED, error = "AUDIO_FAILED: retry"),
        )
        val book = LibraryDisplayMapper.mapBooks(
            projects = listOf(project),
            chapters = chapters,
            segments = segments,
            runs = listOf(run(project.id)),
            positions = listOf(PlaybackPositionEntity(project.id, "chapter-0", positionMs = 5_000, updatedAt = 1)),
            fileSize = mapOf("source" to 100L, "cover" to 50L, "chapter-0.md" to 10L, "chapter-1.md" to 11L)::getValue,
        ).single()

        assertEquals(1, book.generationProgress.completed)
        assertEquals(2, book.generationProgress.total)
        assertEquals(1, book.chapters[0].progress.completed)
        assertEquals(0.25f, book.listeningProgress?.fraction)
        assertEquals(191L, book.storageBytes)
        assertTrue(book.failures.any { it.contains("CHAPTER_FAILED") })
        assertTrue(book.failures.any { it.contains("AUDIO_FAILED") })
    }

    @Test
    public fun routeKeepsBookSelectionInExistingNavigation() {
        assertEquals("start", AppRoute.Start.path)
        assertEquals("book/book-1", AppRoute.Book.forId("book-1"))
        assertEquals("book/{bookId}", AppRoute.Book.path)
    }

    private fun project() = BookProjectEntity(
        id = "book-1",
        title = "Књига",
        author = "Аутор",
        sourceUri = "content://book",
        sourceFingerprint = "a".repeat(64),
        sourcePath = "source",
        coverPath = "cover",
        status = BookProjectStatus.GENERATING,
        lastError = "PROJECT_WARNING: partial",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(id: String, ordinal: Int, status: ChapterStatus, error: String? = null) = ChapterEntity(
        id = id,
        bookProjectId = "book-1",
        ordinal = ordinal,
        title = "Поглавље ${ordinal + 1}",
        canonicalMarkdownPath = "chapter-$ordinal.md",
        status = status,
        lastError = error,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun segment(
        id: String,
        chapterId: String,
        status: AudioSegmentStatus,
        size: Long? = null,
        duration: Long? = null,
        error: String? = null,
    ) = AudioSegmentEntity(
        id = id,
        chapterId = chapterId,
        narrationBlockId = "block-$id",
        sequence = 0,
        chunkOrdinal = 0,
        status = status,
        sizeBytes = size,
        durationMs = duration,
        lastError = error,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun run(projectId: String) = GenerationRunEntity(
        id = "run-1",
        bookProjectId = projectId,
        preprocessingVersion = "test",
        pronunciationVersion = "test",
        inferenceSettingsHash = "test",
        audioProcessingVersion = "test",
        status = GenerationRunStatus.RUNNING,
        requestedAt = 1,
    )
}
