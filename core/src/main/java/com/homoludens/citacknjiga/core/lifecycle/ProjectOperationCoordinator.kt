package com.homoludens.citacknjiga.core.lifecycle

import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Coordinates project deletion with every operation that can publish an artifact. */
public class ProjectOperationCoordinator(
    private val database: AudiobookDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.audiobookDao()

    /** Persists the deletion marker while holding the same lock used by publication. */
    public fun beginDeletion(projectId: String): Boolean = withProjectLock(projectId) {
        var marked = false
        database.runInTransaction {
            val project = dao.findProjectById(projectId)
            marked = project != null && (project.isDeleting || dao.markProjectDeleting(projectId, clock()) == 1)
        }
        marked
    }

    /** Runs a publication only while the project still exists and is not deleting. */
    public fun <T> withPublicationLock(projectId: String, action: () -> T): T? =
        withProjectLock(projectId) {
            val project = dao.findProjectById(projectId)
            if (project == null || project.isDeleting) null else action()
        }

    /** Shared lock boundary for the deletion execution added by task 1.4. */
    public fun <T> withProjectLock(projectId: String, action: () -> T): T {
        require(projectId.isNotBlank()) { "Project id cannot be blank" }
        return locks.computeIfAbsent(projectId) { ReentrantLock(true) }.withLock(action)
    }

    private companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
