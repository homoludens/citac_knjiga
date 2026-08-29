package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus

/** Persists validated state changes as one transaction with their related checks. */
public class GenerationStateService(
    private val database: AudiobookDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.audiobookDao()

    public fun transitionProject(
        projectId: String,
        to: BookProjectStatus,
        error: GenerationError? = null,
    ): BookProjectEntity = inTransaction {
        val current = dao.findProjectById(projectId) ?: missing("project", projectId)
        GenerationStateValidator.validateProject(current.status, to)
        if (to == BookProjectStatus.COMPLETED && dao.findAllChapters()
                .filter { it.bookProjectId == projectId }
                .any { it.status != ChapterStatus.READY }
        ) {
            throw StateConsistencyException("Project $projectId cannot complete while a chapter is not ready")
        }
        val updatedAt = clock()
        checkUpdated(
            dao.transitionProject(
                projectId = projectId,
                fromStatus = current.status,
                toStatus = to,
                lastError = projectError(current, to, error),
                updatedAt = updatedAt,
            ),
            "project",
            projectId,
        )
        dao.findProjectById(projectId)!!
    }

    public fun transitionChapter(
        chapterId: String,
        to: ChapterStatus,
        error: GenerationError? = null,
    ): ChapterEntity = inTransaction {
        val current = dao.findChapterById(chapterId) ?: missing("chapter", chapterId)
        val project = dao.findProjectById(current.bookProjectId)
            ?: throw StateConsistencyException("Chapter $chapterId references missing project ${current.bookProjectId}")
        GenerationStateValidator.validateChapter(current.status, to)
        val chapter = dao.findChapterWithRelations(chapterId)!!
        if (to == ChapterStatus.GENERATING && project.status != BookProjectStatus.GENERATING) {
            throw StateConsistencyException("Chapter $chapterId can generate only while its project is GENERATING")
        }
        if (to == ChapterStatus.READY && chapter.audioSegments.any { it.status != AudioSegmentStatus.READY }) {
            throw StateConsistencyException("Chapter $chapterId cannot be ready while an audio segment is not ready")
        }
        val updatedAt = clock()
        checkUpdated(
            dao.transitionChapter(
                chapterId = chapterId,
                fromStatus = current.status,
                toStatus = to,
                lastError = chapterError(current.lastError, to, error),
                updatedAt = updatedAt,
            ),
            "chapter",
            chapterId,
        )
        dao.findChapterById(chapterId)!!
    }

    public fun transitionGenerationRun(
        runId: String,
        to: GenerationRunStatus,
        error: GenerationError? = null,
    ): GenerationRunEntity = inTransaction {
        val current = dao.findGenerationRunById(runId) ?: missing("generation run", runId)
        val project = dao.findProjectById(current.bookProjectId)
            ?: throw StateConsistencyException("Generation run $runId references missing project ${current.bookProjectId}")
        GenerationStateValidator.validateRun(current.status, to)
        if (to == GenerationRunStatus.RUNNING && project.status != BookProjectStatus.GENERATING) {
            throw StateConsistencyException("Generation run $runId can run only while its project is GENERATING")
        }
        if (to == GenerationRunStatus.COMPLETED && dao.findAllAudioSegments()
                .filter { it.generationRunId == runId }
                .any { it.status != AudioSegmentStatus.READY }
        ) {
            throw StateConsistencyException("Generation run $runId cannot complete while an assigned segment is not ready")
        }
        val now = clock()
        val attemptIncrement = if (to == GenerationRunStatus.RUNNING) 1 else 0
        checkUpdated(
            dao.transitionGenerationRun(
                runId = runId,
                fromStatus = current.status,
                toStatus = to,
                attemptIncrement = attemptIncrement,
                lastError = runError(current.lastError, to, error),
                startedAt = when (to) {
                    GenerationRunStatus.RUNNING -> current.startedAt ?: now
                    GenerationRunStatus.QUEUED -> null
                    else -> current.startedAt
                },
                finishedAt = if (to == GenerationRunStatus.COMPLETED ||
                    to == GenerationRunStatus.FAILED ||
                    to == GenerationRunStatus.CANCELLED
                ) now else null,
            ),
            "generation run",
            runId,
        )
        dao.findGenerationRunById(runId)!!
    }

    public fun transitionAudioSegment(
        segmentId: String,
        to: AudioSegmentStatus,
        error: GenerationError? = null,
    ): AudioSegmentEntity = inTransaction {
        val current = dao.findAudioSegmentById(segmentId) ?: missing("audio segment", segmentId)
        val chapter = dao.findChapterById(current.chapterId)
            ?: throw StateConsistencyException("Audio segment $segmentId references missing chapter ${current.chapterId}")
        val block = dao.findNarrationBlockById(current.narrationBlockId)
            ?: throw StateConsistencyException("Audio segment $segmentId references missing block ${current.narrationBlockId}")
        if (block.chapterId != chapter.id) {
            throw StateConsistencyException("Audio segment $segmentId references a block from another chapter")
        }
        val run = current.generationRunId?.let { runId ->
            dao.findGenerationRunById(runId)
                ?: throw StateConsistencyException("Audio segment $segmentId references missing run $runId")
        }
        val project = dao.findProjectById(chapter.bookProjectId)
            ?: throw StateConsistencyException("Chapter ${chapter.id} references missing project ${chapter.bookProjectId}")
        if (run != null && run.bookProjectId != project.id) {
            throw StateConsistencyException("Audio segment $segmentId is assigned to a run from another project")
        }
        GenerationStateValidator.validateSegment(current.status, to)
        if (to == AudioSegmentStatus.GENERATING) {
            if (chapter.status != ChapterStatus.GENERATING && chapter.status != ChapterStatus.PARTIAL) {
                throw StateConsistencyException("Audio segment $segmentId can generate only in a generating chapter")
            }
            if (run?.status != GenerationRunStatus.RUNNING) {
                throw StateConsistencyException("Audio segment $segmentId requires a RUNNING generation run")
            }
        }
        val now = clock()
        checkUpdated(
            dao.transitionAudioSegment(
                segmentId = segmentId,
                fromStatus = current.status,
                toStatus = to,
                attemptIncrement = if (to == AudioSegmentStatus.GENERATING) 1 else 0,
                lastError = segmentError(current.lastError, to, error),
                updatedAt = now,
            ),
            "audio segment",
            segmentId,
        )
        dao.findAudioSegmentById(segmentId)!!
    }

    public fun retryGenerationRun(runId: String): GenerationRunEntity =
        transitionGenerationRun(runId, GenerationRunStatus.QUEUED)

    public fun retryAudioSegment(segmentId: String): AudioSegmentEntity =
        transitionAudioSegment(segmentId, AudioSegmentStatus.PENDING)

    private fun projectError(
        current: BookProjectEntity,
        to: BookProjectStatus,
        error: GenerationError?,
    ): String? = error?.record ?: when (to) {
        BookProjectStatus.FAILED -> errorRequired("project")
        BookProjectStatus.READY, BookProjectStatus.COMPLETED -> null
        else -> current.lastError
    }

    private fun chapterError(
        previous: String?,
        to: ChapterStatus,
        error: GenerationError?,
    ): String? = error?.record ?: when (to) {
        ChapterStatus.FAILED -> errorRequired("chapter")
        ChapterStatus.READY -> null
        else -> previous
    }

    private fun runError(
        previous: String?,
        to: GenerationRunStatus,
        error: GenerationError?,
    ): String? = error?.record ?: when (to) {
        GenerationRunStatus.FAILED -> errorRequired("generation run")
        GenerationRunStatus.COMPLETED -> null
        else -> previous
    }

    private fun segmentError(
        previous: String?,
        to: AudioSegmentStatus,
        error: GenerationError?,
    ): String? = error?.record ?: when (to) {
        AudioSegmentStatus.FAILED -> errorRequired("audio segment")
        AudioSegmentStatus.READY -> null
        else -> previous
    }

    private fun errorRequired(entityType: String): Nothing =
        throw IllegalArgumentException("A $entityType failure requires an actionable error")

    private fun <T> inTransaction(block: () -> T): T {
        var result: T? = null
        database.runInTransaction { result = block() }
        return result!!
    }

    private fun checkUpdated(rows: Int, entityType: String, id: String) {
        if (rows != 1) {
            throw IllegalStateException("$entityType $id changed before its transition was persisted")
        }
    }

    private fun missing(entityType: String, id: String): Nothing =
        throw StateConsistencyException("Missing $entityType $id")
}
