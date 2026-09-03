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
import com.homoludens.citacknjiga.core.database.GenerationProgressSnapshot
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import com.homoludens.citacknjiga.core.generation.ActiveGenerationProgress
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
        assertEquals("text-preview/book-1", AppRoute.TextPreview.forId("book-1"))
        assertEquals("text-preview/{bookId}", AppRoute.TextPreview.path)
    }

    @Test
    public fun deletingProjectsAreNotShownInLibrary() {
        assertTrue(
            LibraryDisplayMapper.mapBooks(
                projects = listOf(project().copy(isDeleting = true)),
                chapters = emptyList(),
                segments = emptyList(),
                runs = emptyList(),
                positions = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    public fun mapperUsesReadyWordsForChapterAndBookProgress() {
        val project = project()
        val chapters = listOf(chapter("chapter-0", 0, ChapterStatus.PARTIAL))
        val segments = listOf(
            segment("ready", "chapter-0", AudioSegmentStatus.READY),
            segment("pending", "chapter-0", AudioSegmentStatus.GENERATING),
            segment("failed", "chapter-0", AudioSegmentStatus.FAILED),
        )
        val snapshot = GenerationProgressSnapshot(
            scopeId = "chapter-0",
            completedWords = 12,
            totalWords = 100,
            completedSegments = 1,
            totalSegments = 3,
            estimatedSegments = 3,
            generationStatus = GenerationRunStatus.RUNNING,
        )
        val bookSnapshot = snapshot.copy(
            scopeId = project.id,
            completedWords = 12,
            totalWords = 100,
        )

        val book = LibraryDisplayMapper.mapBook(
            project = project,
            chapters = chapters,
            segments = segments.map { it.copy(generationRunId = "run-1") },
            runs = listOf(run(project.id)),
            positions = emptyList(),
            chapterProgress = listOf(snapshot),
            bookProgress = bookSnapshot,
            activeProgress = {
                ActiveGenerationProgress("run-1", "pending", completedWords = 20, totalWords = 88, temporaryWavBytes = 4_096)
            },
        )

        assertEquals(32L, book.chapters.single().progress.completedWords)
        assertEquals(100L, book.chapters.single().progress.totalWords)
        assertEquals(32, book.chapters.single().progress.percentage)
        assertEquals(12, snapshot.percentage)
        assertEquals(GenerationRunStatus.RUNNING, book.chapters.single().generationStatus)
        assertEquals(32L, book.generationProgress.completedWords)
        assertEquals(32, book.generationProgress.percentage)
        assertEquals(GenerationRunStatus.RUNNING, book.generationStatus)
        assertEquals(4_096L, book.activeGenerationProgress?.temporaryWavBytes)
    }

    @Test
    public fun mapperFallsBackToSegmentCountsWhenAllEstimatesAreMissing() {
        val project = project()
        val chapters = listOf(chapter("chapter-0", 0, ChapterStatus.PARTIAL))
        val segments = listOf(
            segment("ready", "chapter-0", AudioSegmentStatus.READY),
            segment("pending", "chapter-0", AudioSegmentStatus.PENDING),
        )
        val snapshot = GenerationProgressSnapshot(
            scopeId = "chapter-0",
            completedWords = 0,
            totalWords = 0,
            completedSegments = 1,
            totalSegments = 2,
            estimatedSegments = 0,
            generationStatus = GenerationRunStatus.PAUSED,
        )

        val book = LibraryDisplayMapper.mapBook(
            project = project,
            chapters = chapters,
            segments = segments,
            runs = emptyList(),
            positions = emptyList(),
            chapterProgress = listOf(snapshot),
            bookProgress = snapshot.copy(scopeId = project.id),
        )

        assertEquals(1L, book.chapters.single().progress.completedWords)
        assertEquals(2L, book.chapters.single().progress.totalWords)
        assertEquals(50, book.chapters.single().progress.percentage)
        assertEquals(50, snapshot.percentage)
        assertEquals(GenerationRunStatus.PAUSED, book.generationStatus)
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
