package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.lifecycle.ProjectOperationCoordinator
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File

/** Removes one regeneration scope before handing its replacement to the durable queue. */
public class GenerationInvalidationCoordinator(
    private val database: AudiobookDatabase,
    private val storage: AppPrivateStorage,
    private val generationCoordinator: DurableGenerationCoordinator,
    private val operations: ProjectOperationCoordinator = ProjectOperationCoordinator(database),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.audiobookDao()

    /** Destructively invalidates the selected scope and queues its replacement. */
    public fun invalidateAndQueue(request: GenerationRequest): QueuedGeneration =
        operations.withProjectLock(request.projectId) {
            val project = dao.findProjectById(request.projectId) ?: error("Project does not exist")
            check(!project.isDeleting) { "Project ${project.id} is being deleted" }
            require(project.sourceFingerprint == request.sourceFingerprint) {
                "Generation request source does not match the project"
            }
            check(project.status != BookProjectStatus.IMPORTING) {
                "Project ${project.id} is still importing"
            }

            val selectedChapters = selectedChapters(request)
            validateRequest(request, selectedChapters)
            val selectedChapterIds = selectedChapters.mapTo(mutableSetOf()) { it.id }
            val oldSegments = dao.findAllAudioSegments().filter { it.chapterId in selectedChapterIds }
            val oldSegmentIds = oldSegments.map(AudioSegmentEntity::id)

            database.runInTransaction {
                oldSegments.forEach { segment ->
                    dao.updateAudioSegment(segment.invalidated(clock()))
                }
                selectedChapters.forEach { chapter ->
                    if (chapter.status != ChapterStatus.PENDING) {
                        dao.updateChapter(chapter.copy(status = ChapterStatus.PENDING, lastError = null, updatedAt = clock()))
                    }
                }
                val currentProject = dao.findProjectById(project.id)!!
                if (currentProject.status != BookProjectStatus.GENERATING &&
                    currentProject.status != BookProjectStatus.READY
                ) {
                    dao.updateProject(currentProject.copy(status = BookProjectStatus.READY, lastError = null, updatedAt = clock()))
                }
            }

            deleteOldAudio(oldSegments)
            if (oldSegmentIds.isNotEmpty()) {
                database.runInTransaction { dao.deleteAudioSegments(oldSegmentIds) }
            }
            generationCoordinator.queue(request)
        }

    private fun selectedChapters(request: GenerationRequest): List<ChapterEntity> = when (val scope = request.scope) {
        GenerationScope.CompleteBook -> dao.findAllChapters()
            .filter { it.bookProjectId == request.projectId }
            .sortedBy { it.ordinal }
        is GenerationScope.Chapter -> listOf(
            dao.findChapterById(scope.chapterId)
                ?.also { require(it.bookProjectId == request.projectId) {
                    "Chapter ${scope.chapterId} does not belong to project ${request.projectId}"
                } }
                ?: error("Chapter ${scope.chapterId} does not exist"),
        )
    }

    private fun validateRequest(request: GenerationRequest, chapters: List<ChapterEntity>) {
        val project = dao.findProjectById(request.projectId)!!
        val expected = GenerationRequestFactory.fromExistingNarrationBlocks(
            project = project,
            chapters = chapters,
            narrationBlocks = dao.findAllNarrationBlocks(),
            scope = request.scope,
            engine = request.engine,
        )
        require(request.narrationBlocks == expected.narrationBlocks) {
            "Generation request does not cover the selected narration scope"
        }
    }

    private fun deleteOldAudio(segments: List<AudioSegmentEntity>) {
        val readyRoot = storage.readyAudioDirectory.canonicalFile.toPath()
        segments.mapNotNull { it.audioPath?.let(::File) }.map { it.canonicalFile }.distinct().forEach { file ->
            // An invalid legacy path is made non-playable by Room reset, never deleted.
            if (!file.toPath().startsWith(readyRoot)) return@forEach
            if (file.isFile && !file.delete()) {
                throw IllegalStateException("Could not remove old audio ${file.name}")
            }
        }
    }

    private fun AudioSegmentEntity.invalidated(updatedAt: Long): AudioSegmentEntity = copy(
        generationKey = null,
        generationRunId = null,
        modelPackageId = null,
        modelPackageSha256 = null,
        voiceSha256 = null,
        preprocessingVersion = null,
        pronunciationVersion = null,
        inferenceSettingsHash = null,
        audioProcessingVersion = null,
        status = AudioSegmentStatus.STALE,
        audioPath = null,
        audioSha256 = null,
        sizeBytes = null,
        durationMs = null,
        attemptCount = 0,
        lastError = null,
        updatedAt = updatedAt,
        engine = null,
        modelRevision = null,
        speakerId = null,
        frontendVersion = null,
        nativeSampleRate = null,
        finalSampleRate = null,
        resamplerVersion = null,
        runtimeId = null,
        runtimeVersion = null,
    )
}
