package com.homoludens.citacknjiga.core.generation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.reconciliation.ReconciliationReport
import com.homoludens.citacknjiga.core.reconciliation.RoomReconciliationDatabase
import com.homoludens.citacknjiga.core.reconciliation.StartupReconciliation
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

public fun interface GenerationRunExecutor {
    public suspend fun execute(runId: String): BoundedGenerationResult
}

public interface GenerationQueue {
    public fun reconcile(): ReconciliationReport

    public fun queuedRunIds(): List<String>
}

public class RoomGenerationQueue(
    database: AudiobookDatabase,
    private val storage: AppPrivateStorage,
) : GenerationQueue {
    private val dao = database.audiobookDao()
    private val reconciliation = StartupReconciliation(
        database = RoomReconciliationDatabase(database),
        storage = storage,
    )

    override fun reconcile(): ReconciliationReport = reconciliation.reconcile()

    override fun queuedRunIds(): List<String> = dao.findAllGenerationRuns()
        .filter { it.status == GenerationRunStatus.QUEUED }
        .map { it.id }
}

public object GenerationWorkContract {
    public const val RUN_ID_KEY: String = "generation_run_id"
    public const val STATUS_KEY: String = "generation_status"
    public const val FAILED_SEGMENTS_KEY: String = "failed_segment_ids"
    public const val UNIQUE_WORK_PREFIX: String = "generation-run-"
    public const val TAG_PREFIX: String = "generation-run:"

    public fun uniqueWorkName(runId: String): String = UNIQUE_WORK_PREFIX + runId

    public fun tag(runId: String): String = TAG_PREFIX + runId
}

/** Executes the bounded runner without owning notification or foreground policy. */
public class GenerationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val executor: GenerationRunExecutor,
    private val notifications: GenerationNotificationController? = null,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runId = inputData.getString(GenerationWorkContract.RUN_ID_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure(workDataOf("error" to "Missing generation run id"))
        return try {
            notifications?.let { setForeground(it.foregroundInfo(runId)) }
            val result = if (notifications == null) {
                executor.execute(runId)
            } else {
                coroutineScope {
                    val monitor = launch {
                        while (isActive) {
                            val foreground = notifications.foregroundInfo(runId)
                            setProgress(notifications.progressData(runId))
                            setForeground(foreground)
                            delay(PROGRESS_REFRESH_MILLIS)
                        }
                    }
                    try {
                        executor.execute(runId)
                    } finally {
                        monitor.cancel()
                    }
                }
            }
            val output = workDataOf(
                GenerationWorkContract.RUN_ID_KEY to result.runId,
                GenerationWorkContract.STATUS_KEY to result.status.name,
                GenerationWorkContract.FAILED_SEGMENTS_KEY to result.failedSegmentIds.toTypedArray(),
            )
            if (result.status == BoundedGenerationStatus.FAILED) {
                Result.failure(output)
            } else {
                Result.success(output)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            Result.failure(workDataOf("error" to (failure.message ?: "Invalid generation state")))
        } catch (failure: Throwable) {
            Result.retry()
        }
    }

    private companion object {
        const val PROGRESS_REFRESH_MILLIS = 1_000L
    }
}

/** Supplies the runner after process recreation without coupling it to WorkManager. */
public class GenerationWorkerFactory(
    private val executor: GenerationRunExecutor,
    private val notifications: GenerationNotificationController? = null,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == GenerationWorker::class.java.name) {
        GenerationWorker(appContext, workerParameters, executor, notifications)
    } else {
        null
    }
}

public class GenerationWorkScheduler(
    private val workManager: WorkManager,
    private val queue: GenerationQueue,
    private val constraints: Constraints = defaultConstraints(),
) {
    /** Reconciles durable state first, then lets KEEP preserve an existing run. */
    public fun enqueue(runId: String): Operation {
        require(runId.isNotBlank()) { "Generation run id cannot be blank" }
        queue.reconcile()
        check(runId in queue.queuedRunIds()) { "Generation run $runId is not queued" }
        return enqueueUnique(runId)
    }

    /** Intended for app start, reboot, and update reconciliation. */
    public fun reconcileAndEnqueue(): List<String> {
        queue.reconcile()
        return queue.queuedRunIds()
            .distinct()
            .sorted()
            .onEach(::enqueueUnique)
    }

    public fun cancel(runId: String): Operation = workManager.cancelUniqueWork(
        GenerationWorkContract.uniqueWorkName(runId),
    )

    private fun enqueueUnique(runId: String): Operation {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(workDataOf(GenerationWorkContract.RUN_ID_KEY to runId))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .addTag(GenerationWorkContract.tag(runId))
            .build()
        return workManager.enqueueUniqueWork(
            GenerationWorkContract.uniqueWorkName(runId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    public companion object {
        public fun defaultConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
