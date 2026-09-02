package com.homoludens.citacknjiga.core.lifecycle

import androidx.work.WorkManager
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.generation.GenerationWorkContract
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.ProjectArtifactCleanupPolicy
import com.homoludens.citacknjiga.core.storage.projectArtifactInventory

public fun interface ProjectPlaybackStopper {
    public fun stop(projectId: String)
}

public fun interface ProjectWorkCanceller {
    public fun cancel(runId: String)
}

public class WorkManagerProjectWorkCanceller(
    private val workManager: WorkManager,
) : ProjectWorkCanceller {
    override fun cancel(runId: String) {
        workManager.cancelUniqueWork(GenerationWorkContract.uniqueWorkName(runId))
    }
}

public data class ProjectDeletionResult(
    public val projectId: String,
    public val targetedFileCount: Int,
    public val deletedFileCount: Int,
)

public data class ProjectDeletionRecoveryReport(
    public val deletedProjectIds: List<String>,
    public val unfinishedProjectIds: List<String>,
)

/** Performs the durable, private-artifact deletion protocol for one project. */
public class ProjectDeletionCoordinator(
    private val database: AudiobookDatabase,
    private val storage: AppPrivateStorage,
    private val workCanceller: ProjectWorkCanceller,
    private val playbackStopper: ProjectPlaybackStopper,
    private val operations: ProjectOperationCoordinator = ProjectOperationCoordinator(database),
    private val cleanupPolicy: ProjectArtifactCleanupPolicy = ProjectArtifactCleanupPolicy(storage),
) {
    private val dao = database.audiobookDao()

    /** Stops external owners before taking the project lock, then completes under that lock. */
    public fun deleteProject(projectId: String): ProjectDeletionResult? {
        val project = dao.findProjectById(projectId) ?: return null
        playbackStopper.stop(projectId)
        dao.findAllGenerationRuns()
            .filter { it.bookProjectId == projectId }
            .forEach { workCanceller.cancel(it.id) }
        return operations.withProjectLock(projectId) {
            val current = dao.findProjectById(projectId) ?: return@withProjectLock null
            check(operations.beginDeletion(projectId)) { "Project $projectId could not be marked deleting" }
            finishDeletion(projectId, current)
        }
    }

    /** Repeats interrupted deletion after process death; a failed project remains marked. */
    public fun reconcileDeletingProjects(): ProjectDeletionRecoveryReport {
        val deletingIds = dao.findAllProjects().filter { it.isDeleting }.map { it.id }.sorted()
        val deleted = mutableListOf<String>()
        val unfinished = mutableListOf<String>()
        deletingIds.forEach { projectId ->
            runCatching { deleteProject(projectId) }
                .onSuccess { if (it != null) deleted += projectId else unfinished += projectId }
                .onFailure { unfinished += projectId }
        }
        return ProjectDeletionRecoveryReport(deleted.sorted(), unfinished.distinct().sorted())
    }

    private fun finishDeletion(
        projectId: String,
        markedProject: BookProjectEntity,
    ): ProjectDeletionResult {
        val chapters = dao.findAllChapters().filter { it.bookProjectId == projectId }
        val chapterIds = chapters.mapTo(mutableSetOf()) { it.id }
        val segments = dao.findAllAudioSegments().filter { it.chapterId in chapterIds }
        val runs = dao.findAllGenerationRuns().filter { it.bookProjectId == projectId }
        val inventory = storage.projectArtifactInventory(
            project = markedProject,
            chapters = chapters,
            audioSegments = segments,
            generationRuns = runs,
        )
        val cleanup = cleanupPolicy.cleanup(inventory)
        var deletedRows = 0
        database.runInTransaction {
            deletedRows = database.openHelper.writableDatabase.delete(
                "book_project",
                "id = ?",
                arrayOf(projectId),
            )
        }
        check(deletedRows == 1) { "Project $projectId disappeared before deletion" }
        return ProjectDeletionResult(projectId, cleanup.targetedFiles.size, cleanup.deletedFiles.size)
    }
}
