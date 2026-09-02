package com.homoludens.citacknjiga.library

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.GenerationEngine
import com.homoludens.citacknjiga.core.generation.GenerationRequest
import com.homoludens.citacknjiga.core.generation.GenerationScope
import com.homoludens.citacknjiga.core.generation.QueuedGeneration
import com.homoludens.citacknjiga.tts.onnx.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class LibraryRegenerationControllerTest {
    @Test
    public fun chapterAndBookRequestsUseCurrentEngineAndScope() {
        val requests = mutableListOf<GenerationRequest>()
        var selected = TtsEngine.VITS
        val controller = controller(requests, selectedEngine = { selected })

        val chapterResult = controller.regenerate("book", GenerationScope.Chapter("chapter-1"))
        selected = TtsEngine.KOKORO
        val bookResult = controller.regenerate("book", GenerationScope.CompleteBook)

        assertEquals(RegenerationResultStatus.QUEUED, chapterResult.status)
        assertEquals(RegenerationResultStatus.QUEUED, bookResult.status)
        assertEquals(GenerationScope.Chapter("chapter-1"), requests[0].scope)
        assertEquals(GenerationEngine.VITS, requests[0].engine)
        assertEquals(listOf("block-1"), requests[0].narrationBlocks.map { it.id })
        assertEquals(GenerationScope.CompleteBook, requests[1].scope)
        assertEquals(GenerationEngine.KOKORO, requests[1].engine)
        assertEquals(listOf("block-1", "block-2"), requests[1].narrationBlocks.map { it.id })
    }

    @Test
    public fun failedQueueIsActionableAndRetryUsesTheSameScope() {
        val requests = mutableListOf<GenerationRequest>()
        var attempts = 0
        val controller = controller(
            requests = requests,
            invalidate = {
                attempts++
                if (attempts == 1) error("model unavailable")
                QueuedGeneration("retry-run", listOf("retry-segment"))
            },
        )

        val failed = controller.regenerate("book", GenerationScope.Chapter("chapter-1"))
        val retried = controller.regenerate("book", GenerationScope.Chapter("chapter-1"))

        assertEquals(RegenerationResultStatus.FAILED, failed.status)
        assertEquals(RegenerationResultStatus.QUEUED, retried.status)
        assertEquals(2, requests.size)
        assertEquals(requests[0].scope, requests[1].scope)
        assertTrue(retried.queued?.runId == "retry-run")
    }

    @Test
    public fun runRetryReconstructsChapterScopeBeforeRequeue() {
        val requests = mutableListOf<GenerationRequest>()
        val controller = controller(
            requests = requests,
            run = GenerationRunEntity(
                id = "failed-run",
                bookProjectId = "book",
                preprocessingVersion = "prep",
                pronunciationVersion = "pron",
                inferenceSettingsHash = "settings",
                audioProcessingVersion = "audio",
                status = GenerationRunStatus.FAILED,
                requestedAt = 1,
            ),
            segments = listOf(
                AudioSegmentEntity(
                    id = "failed-segment",
                    chapterId = "chapter-1",
                    narrationBlockId = "block-1",
                    sequence = 0,
                    chunkOrdinal = 0,
                    generationRunId = "failed-run",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )

        val result = controller.retry("failed-run")

        assertEquals(RegenerationResultStatus.QUEUED, result?.status)
        assertEquals(GenerationScope.Chapter("chapter-1"), requests.single().scope)
    }

    private fun controller(
        requests: MutableList<GenerationRequest>,
        selectedEngine: () -> TtsEngine = { TtsEngine.KOKORO },
        invalidate: (GenerationRequest) -> QueuedGeneration = { request ->
            QueuedGeneration("run-${requests.size}", request.narrationBlocks.map { "segment-${it.id}" })
        },
        run: GenerationRunEntity? = null,
        segments: Collection<AudioSegmentEntity> = emptyList(),
    ): LibraryRegenerationController {
        val project = BookProjectEntity(
            id = "book",
            title = "Book",
            sourceUri = "content://book",
            sourceFingerprint = "fingerprint",
            status = BookProjectStatus.COMPLETED,
            createdAt = 1,
            updatedAt = 1,
        )
        val chapters = listOf(
            ChapterEntity("chapter-1", "book", 0, "One", status = ChapterStatus.READY, createdAt = 1, updatedAt = 1),
            ChapterEntity("chapter-2", "book", 1, "Two", status = ChapterStatus.READY, createdAt = 1, updatedAt = 1),
        )
        val blocks = listOf(
            NarrationBlockEntity("block-1", "chapter-1", 0, NarrationBlockType.PARAGRAPH, "First", createdAt = 1, updatedAt = 1),
            NarrationBlockEntity("block-2", "chapter-2", 0, NarrationBlockType.PARAGRAPH, "Second", createdAt = 1, updatedAt = 1),
        )
        return LibraryRegenerationController(
            findProject = { project },
            findChapters = { chapters },
            findNarrationBlocks = { blocks },
            findRun = { run },
            findSegments = { segments },
            invalidateAndQueue = { request ->
                requests += request
                invalidate(request)
            },
            selectedEngine = selectedEngine,
        )
    }
}
