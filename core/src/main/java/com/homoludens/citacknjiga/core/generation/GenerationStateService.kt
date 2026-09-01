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
import com.homoludens.citacknjiga.core.storage.PublishedArtifact

/** Persists validated state changes as one transaction with their related checks. */
public class GenerationStateService(
    private val database: AudiobookDatabase,
    private val retryPolicy: GenerationRetryPolicy = GenerationRetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : GenerationStateGateway {
    private val dao = database.audiobookDao()

    override fun findGenerationRun(runId: String): GenerationRunEntity? = dao.findGenerationRunById(runId)

    override fun startGenerationRun(runId: String): GenerationRunEntity = inTransaction {
        val run = dao.findGenerationRunById(runId) ?: missing("generation run", runId)
        if (run.status != GenerationRunStatus.QUEUED) return@inTransaction run
        val project = dao.findProjectById(run.bookProjectId)
            ?: throw StateConsistencyException("Generation run $runId references missing project ${run.bookProjectId}")
        if (project.status != BookProjectStatus.GENERATING) {
            GenerationStateValidator.validateProject(project.status, BookProjectStatus.GENERATING)
            checkUpdated(
                dao.transitionProject(
                    project.id,
                    project.status,
                    BookProjectStatus.GENERATING,
                    project.lastError,
                    clock(),
                ),
                "project",
                project.id,
            )
        }
        dao.findAllAudioSegments()
            .filter { it.generationRunId == runId }
            .map { it.chapterId }
            .toSet()
            .forEach { chapterId ->
                val chapter = dao.findChapterById(chapterId)
                    ?: throw StateConsistencyException("Audio segment references missing chapter $chapterId")
                if (chapter.status != ChapterStatus.GENERATING) {
                    GenerationStateValidator.validateChapter(chapter.status, ChapterStatus.GENERATING)
                    checkUpdated(
                        dao.transitionChapter(
                            chapter.id,
                            chapter.status,
                            ChapterStatus.GENERATING,
                            chapter.lastError,
                            clock(),
                        ),
                        "chapter",
                        chapter.id,
                    )
                }
            }
        checkUpdated(
            dao.transitionGenerationRun(
                runId,
                GenerationRunStatus.QUEUED,
                GenerationRunStatus.RUNNING,
                attemptIncrement = 1,
                lastError = run.lastError,
                startedAt = clock(),
                finishedAt = null,
            ),
            "generation run",
            runId,
        )
        dao.findGenerationRunById(runId)!!
    }

    override fun claimNextSegment(runId: String): ClaimedGenerationSegment? = inTransaction {
        val run = dao.findGenerationRunById(runId) ?: missing("generation run", runId)
        if (run.status != GenerationRunStatus.RUNNING) return@inTransaction null
        val pending = dao.findAllAudioSegments()
            .asSequence()
            .filter { it.generationRunId == runId && it.status == AudioSegmentStatus.PENDING }
            .sortedWith(compareBy<AudioSegmentEntity> { it.sequence }.thenBy { it.id })
        for (candidate in pending) {
            val chapter = dao.findChapterById(candidate.chapterId)
                ?: throw StateConsistencyException("Audio segment ${candidate.id} references missing chapter ${candidate.chapterId}")
            val block = dao.findNarrationBlockById(candidate.narrationBlockId)
                ?: throw StateConsistencyException("Audio segment ${candidate.id} references missing block ${candidate.narrationBlockId}")
            require(chapter.status == ChapterStatus.GENERATING || chapter.status == ChapterStatus.PARTIAL) {
                "Audio segment ${candidate.id} can generate only in a generating chapter"
            }
            require(block.chapterId == chapter.id) {
                "Audio segment ${candidate.id} references a block from another chapter"
            }
            val claimed = dao.transitionAudioSegment(
                candidate.id,
                AudioSegmentStatus.PENDING,
                AudioSegmentStatus.GENERATING,
                attemptIncrement = 1,
                lastError = candidate.lastError,
                updatedAt = clock(),
            )
            if (claimed == 1) {
                return@inTransaction ClaimedGenerationSegment(dao.findAudioSegmentById(candidate.id)!!, block)
            }
        }
        null
    }

    override fun completeAudioSegment(
        segmentId: String,
        published: PublishedArtifact,
        audio: GeneratedSegmentAudio,
    ): AudioSegmentEntity = inTransaction {
        val current = dao.findAudioSegmentById(segmentId) ?: missing("audio segment", segmentId)
        GenerationStateValidator.validateSegment(current.status, AudioSegmentStatus.READY)
        checkUpdated(
            dao.transitionAudioSegment(
                segmentId,
                AudioSegmentStatus.GENERATING,
                AudioSegmentStatus.READY,
                attemptIncrement = 0,
                lastError = null,
                updatedAt = clock(),
            ),
            "audio segment",
            segmentId,
        )
        val updated = dao.findAudioSegmentById(segmentId)!!.copy(
            generationKey = audio.provenance.generationKey,
            modelPackageId = audio.provenance.modelPackageId,
            modelPackageSha256 = audio.provenance.modelPackageSha256,
            voiceSha256 = audio.provenance.voiceSha256,
            preprocessingVersion = audio.provenance.preprocessingVersion,
            pronunciationVersion = audio.provenance.pronunciationVersion,
            inferenceSettingsHash = audio.provenance.inferenceSettingsHash,
            audioProcessingVersion = audio.provenance.audioProcessingVersion,
            engine = audio.provenance.engine,
            modelRevision = audio.provenance.modelRevision,
            speakerId = audio.provenance.speakerId,
            frontendVersion = audio.provenance.frontendVersion,
            nativeSampleRate = audio.provenance.nativeSampleRateHz,
            finalSampleRate = audio.provenance.finalSampleRateHz,
            resamplerVersion = audio.provenance.resamplerVersion,
            runtimeId = audio.provenance.runtimeId,
            runtimeVersion = audio.provenance.runtimeVersion,
            audioPath = published.file.path,
            audioSha256 = published.sha256,
            sizeBytes = published.sizeBytes,
            durationMs = audio.durationMs,
            sampleRate = audio.sampleRateHz,
            channels = audio.channels,
            updatedAt = clock(),
        )
        dao.updateAudioSegment(updated)
        updated
    }

    override fun failAudioSegment(segmentId: String, error: GenerationError): AudioSegmentEntity = inTransaction {
        transitionAudioSegmentInTransaction(segmentId, AudioSegmentStatus.FAILED, error)
    }

    override fun releaseAudioSegment(segmentId: String): AudioSegmentEntity = inTransaction {
        transitionAudioSegmentInTransaction(segmentId, AudioSegmentStatus.PENDING)
    }

    override fun failGenerationRun(runId: String, error: GenerationError): GenerationRunEntity =
        transitionGenerationRun(runId, GenerationRunStatus.FAILED, error)

    override fun finishGenerationRun(runId: String): GenerationRunEntity = inTransaction {
        val run = dao.findGenerationRunById(runId) ?: missing("generation run", runId)
        if (run.status != GenerationRunStatus.RUNNING) return@inTransaction run
        val assigned = dao.findAllAudioSegments().filter { it.generationRunId == runId }
        if (assigned.any { it.status == AudioSegmentStatus.FAILED }) {
            return@inTransaction transitionGenerationRunInTransaction(
                run.id,
                GenerationRunStatus.FAILED,
                GenerationError("SEGMENTS_FAILED", "One or more audio segments failed and can be retried"),
            )
        }
        if (assigned.any { it.status != AudioSegmentStatus.READY }) return@inTransaction run
        assigned.map { it.chapterId }.toSet().forEach { chapterId ->
            val chapter = dao.findChapterById(chapterId) ?: missing("chapter", chapterId)
            val chapterSegments = assigned + dao.findAllAudioSegments().filter { it.chapterId == chapterId }
            if (chapterSegments.all { it.status == AudioSegmentStatus.READY } && chapter.status != ChapterStatus.READY) {
                GenerationStateValidator.validateChapter(chapter.status, ChapterStatus.READY)
                checkUpdated(
                    dao.transitionChapter(chapter.id, chapter.status, ChapterStatus.READY, null, clock()),
                    "chapter",
                    chapter.id,
                )
            }
        }
        val completed = transitionGenerationRunInTransaction(run.id, GenerationRunStatus.COMPLETED)
        val chapters = dao.findAllChapters().filter { it.bookProjectId == run.bookProjectId }
        val project = dao.findProjectById(run.bookProjectId)
        if (project != null && project.status == BookProjectStatus.GENERATING && chapters.all { it.status == ChapterStatus.READY }) {
            GenerationStateValidator.validateProject(project.status, BookProjectStatus.COMPLETED)
            checkUpdated(
                dao.transitionProject(project.id, project.status, BookProjectStatus.COMPLETED, null, clock()),
                "project",
                project.id,
            )
        }
        completed
    }

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
        transitionGenerationRunInTransaction(runId, to, error)
    }

    private fun transitionGenerationRunInTransaction(
        runId: String,
        to: GenerationRunStatus,
        error: GenerationError? = null,
    ): GenerationRunEntity {
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
        return dao.findGenerationRunById(runId)!!
    }

    public fun transitionAudioSegment(
        segmentId: String,
        to: AudioSegmentStatus,
        error: GenerationError? = null,
    ): AudioSegmentEntity = inTransaction {
        transitionAudioSegmentInTransaction(segmentId, to, error)
    }

    private fun transitionAudioSegmentInTransaction(
        segmentId: String,
        to: AudioSegmentStatus,
        error: GenerationError? = null,
    ): AudioSegmentEntity {
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
        return dao.findAudioSegmentById(segmentId)!!
    }

    public fun retryGenerationRun(runId: String): GenerationRunEntity =
        transitionGenerationRun(runId, GenerationRunStatus.QUEUED)

    override fun retryAudioSegment(segmentId: String): AudioSegmentEntity = inTransaction {
        val current = dao.findAudioSegmentById(segmentId) ?: missing("audio segment", segmentId)
        check(retryPolicy.canRetry(current.attemptCount)) {
            "Audio segment $segmentId reached the retry limit of ${retryPolicy.maxAttempts} attempts"
        }
        transitionAudioSegmentInTransaction(segmentId, AudioSegmentStatus.PENDING)
    }

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
        var result: Any? = NO_TRANSACTION_RESULT
        database.runInTransaction { result = block() }
        check(result !== NO_TRANSACTION_RESULT) { "Database transaction did not execute" }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun checkUpdated(rows: Int, entityType: String, id: String) {
        if (rows != 1) {
            throw IllegalStateException("$entityType $id changed before its transition was persisted")
        }
    }

    private fun missing(entityType: String, id: String): Nothing =
        throw StateConsistencyException("Missing $entityType $id")

    private companion object {
        private object NO_TRANSACTION_RESULT
    }
}
