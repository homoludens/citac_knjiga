package com.homoludens.citacknjiga.core.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.work.ForegroundInfo
import androidx.work.Data
import androidx.work.workDataOf
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

public data class GenerationNotificationSnapshot(
    public val title: String,
    public val status: GenerationRunStatus,
    public val totalSegments: Int,
    public val readySegments: Int,
    public val failedSegments: Int,
)

public interface GenerationNotificationDataSource {
    public fun snapshot(runId: String): GenerationNotificationSnapshot
}

public class RoomGenerationNotificationDataSource(
    database: AudiobookDatabase,
) : GenerationNotificationDataSource {
    private val dao = database.audiobookDao()

    override fun snapshot(runId: String): GenerationNotificationSnapshot {
        val run = dao.findGenerationRunById(runId) ?: error("Missing generation run $runId")
        val project = dao.findProjectById(run.bookProjectId) ?: error("Missing project ${run.bookProjectId}")
        val segments = dao.findAllAudioSegments().filter { it.generationRunId == runId }
        return GenerationNotificationSnapshot(
            title = project.title,
            status = run.status,
            totalSegments = segments.size,
            readySegments = segments.count { it.status == AudioSegmentStatus.READY },
            failedSegments = segments.count { it.status == AudioSegmentStatus.FAILED },
        )
    }
}

/** Builds the visible foreground state without deciding which execution host is used. */
public class GenerationNotificationController(
    private val context: Context,
    private val dataSource: GenerationNotificationDataSource,
) {
    public fun foregroundInfo(runId: String): ForegroundInfo {
        val snapshot = dataSource.snapshot(runId)
        ensureChannel()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snapshot.title)
            .setContentText(progressText(snapshot))
            .setProgress(snapshot.totalSegments, snapshot.readySegments, snapshot.totalSegments == 0)
            .setOngoing(snapshot.status == GenerationRunStatus.RUNNING)
            .setOnlyAlertOnce(true)
            .addAction(action(runId, ACTION_CANCEL, "Cancel"))
            .apply {
                when (snapshot.status) {
                    GenerationRunStatus.RUNNING -> addAction(action(runId, ACTION_PAUSE, "Pause"))
                    GenerationRunStatus.PAUSED -> addAction(action(runId, ACTION_RESUME, "Resume"))
                    else -> Unit
                }
            }
            .build()
        return ForegroundInfo(notificationId(runId), notification)
    }

    public fun progressData(runId: String): Data {
        val snapshot = dataSource.snapshot(runId)
        return workDataOf(
            GenerationWorkContract.RUN_ID_KEY to runId,
            GenerationWorkContract.STATUS_KEY to snapshot.status.name,
            "total_segments" to snapshot.totalSegments,
            "ready_segments" to snapshot.readySegments,
            "failed_segments" to snapshot.failedSegments,
        )
    }

    private fun progressText(snapshot: GenerationNotificationSnapshot): String = buildString {
        append(snapshot.readySegments)
        append('/').append(snapshot.totalSegments).append(" segments")
        if (snapshot.failedSegments > 0) {
            append("; ").append(snapshot.failedSegments).append(" failed")
        }
    }

    private fun action(runId: String, action: String, label: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_media_pause),
            label,
            PendingIntent.getBroadcast(
                context,
                (runId + action).hashCode(),
                Intent(context, GenerationNotificationActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(RUN_ID_EXTRA, runId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Audiobook generation",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notificationId(runId: String): Int = 10_000 + (runId.hashCode() and Int.MAX_VALUE) % 10_000

    public companion object {
        public const val CHANNEL_ID: String = "generation"
        public const val ACTION_PAUSE: String = "com.homoludens.citacknjiga.generation.PAUSE"
        public const val ACTION_RESUME: String = "com.homoludens.citacknjiga.generation.RESUME"
        public const val ACTION_CANCEL: String = "com.homoludens.citacknjiga.generation.CANCEL"
        public const val RUN_ID_EXTRA: String = "generation_run_id"
    }
}

/** Applies notification actions to Room before coordinating the unique work. */
public class GenerationNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(GenerationNotificationController.RUN_ID_EXTRA)
            ?.takeIf(String::isNotBlank) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val database = AudiobookDatabase.create(context)
            try {
                val state = GenerationStateService(database)
                when (intent.action) {
                    GenerationNotificationController.ACTION_PAUSE ->
                        state.transitionGenerationRun(runId, GenerationRunStatus.PAUSED)
                    GenerationNotificationController.ACTION_RESUME -> {
                        state.retryGenerationRun(runId)
                        GenerationWorkScheduler(
                            workManager = androidx.work.WorkManager.getInstance(context),
                            queue = RoomGenerationQueue(database, AppPrivateStorage(context.filesDir)),
                        ).enqueue(runId)
                    }
                    GenerationNotificationController.ACTION_CANCEL -> {
                        state.transitionGenerationRun(runId, GenerationRunStatus.CANCELLED)
                        androidx.work.WorkManager.getInstance(context)
                            .cancelUniqueWork(GenerationWorkContract.uniqueWorkName(runId))
                    }
                }
            } finally {
                database.close()
                pendingResult.finish()
            }
        }
    }
}
