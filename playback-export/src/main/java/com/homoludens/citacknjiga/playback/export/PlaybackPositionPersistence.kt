package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public data class RestoredPlaybackPosition(
    public val chapterId: String,
    public val audioSegmentId: String,
    public val mediaItemIndex: Int,
    public val positionMs: Long,
    public val speed: Float,
)

/** Persists the Media3 player's small durable state without taking ownership of playback. */
public class PlaybackPositionPersistence(
    private val dao: AudiobookDao,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
) : AutoCloseable {
    private val writeMutex = Mutex()
    private var binding: Binding? = null
    private var pollJob: Job? = null
    private var lastWriteAt: Long? = null

    public fun attach(projectId: String, catalog: PlaybackCatalog, player: PlaybackPlayerPort) {
        pollJob?.cancel()
        binding?.let { it.player.removeListener(it.listener) }
        lastWriteAt = null
        val listener: () -> Unit = { onPlayerEvent() }
        binding = Binding(projectId, catalog, player, listener)
        player.addListener(listener)
        pollJob = scope.launch {
            persist(force = false)
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                persist(force = false)
            }
        }
    }

    /** Reads only a saved item that still belongs to the current verified catalog. */
    public suspend fun restore(
        projectId: String,
        catalog: PlaybackCatalog,
        player: PlaybackPlayerPort,
    ): RestoredPlaybackPosition? {
        val saved = withContext(Dispatchers.IO) { dao.findPlaybackPosition(projectId) } ?: return null
        val savedChapter = catalog.chapters.firstOrNull { it.id == saved.chapterId && it.available }
        val savedSegment = saved.audioSegmentId?.let { id ->
            catalog.chapters.firstOrNull { it.available && id in it.mediaItemIds }?.let { chapter ->
                id to chapter
            }
        }
        val targetChapter = when {
            savedSegment != null && (savedChapter == null || savedSegment.second.id == savedChapter.id) ->
                savedSegment.second
            savedChapter != null -> savedChapter
            else -> catalog.chapters.firstOrNull { it.available } ?: return null
        }
        val targetSegment = savedSegment
            ?.takeIf { it.second.id == targetChapter.id }
            ?.first
            ?: targetChapter.mediaItemIds.firstOrNull()
            ?: return null
        val index = catalog.mediaItemIds.indexOf(targetSegment)
        if (index < 0) return null
        val speed = saved.speed.takeIf { it.isFinite() && it in SUPPORTED_PLAYBACK_SPEEDS } ?: DEFAULT_SPEED
        val position = if (targetSegment == saved.audioSegmentId && savedSegment?.second?.id == targetChapter.id) {
            clampSeek(saved.positionMs, player.durationMs)
        } else {
            0L
        }
        player.setSpeed(speed)
        player.seekTo(index, position)
        return RestoredPlaybackPosition(targetChapter.id, targetSegment, index, position, speed)
    }

    public fun onPlayerEvent() {
        scope.launch { persist(force = false) }
    }

    public suspend fun persistIfDue() {
        persist(force = false)
    }

    public suspend fun flush() {
        persist(force = true)
    }

    override fun close() {
        pollJob?.cancel()
        pollJob = null
        binding?.let { it.player.removeListener(it.listener) }
        binding = null
    }

    private suspend fun persist(force: Boolean) {
        writeMutex.withLock {
            val current = binding ?: return
            val itemId = current.player.currentMediaItemId ?: return
            val chapter = current.catalog.chapterForMediaItemId(itemId) ?: return
            val speed = current.player.speed.takeIf {
                it.isFinite() && it in SUPPORTED_PLAYBACK_SPEEDS
            } ?: DEFAULT_SPEED
            val timestamp = nowMs()
            val previousWrite = lastWriteAt
            if (!force && previousWrite != null && timestamp >= previousWrite && timestamp - previousWrite < throttleMs) {
                return
            }
            val position = PlaybackPositionEntity(
                bookProjectId = current.projectId,
                chapterId = chapter.id,
                audioSegmentId = itemId,
                positionMs = clampSeek(current.player.positionMs, current.player.durationMs),
                speed = speed,
                updatedAt = timestamp,
            )
            withContext(Dispatchers.IO) { dao.savePlaybackPosition(position) }
            lastWriteAt = timestamp
        }
    }

    private data class Binding(
        val projectId: String,
        val catalog: PlaybackCatalog,
        val player: PlaybackPlayerPort,
        val listener: () -> Unit,
    )

    private companion object {
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_THROTTLE_MS = 2_000L
        const val POLL_INTERVAL_MS = 1_000L
    }
}
