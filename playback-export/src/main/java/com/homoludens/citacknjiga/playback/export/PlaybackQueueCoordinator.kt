package com.homoludens.citacknjiga.playback.export

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.homoludens.citacknjiga.core.database.ChapterEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The single Media3 queue mutation seam owned by the playback service. */
public interface PlaybackQueuePlayerPort {
    public val isPlaying: Boolean
    public val currentPositionMs: Long
    public val currentMediaItemId: String?

    public fun replaceQueue(
        items: List<MediaItem>,
        currentItemIndex: Int,
        positionMs: Long,
        resumePlayback: Boolean,
    )

    public fun addListener(listener: () -> Unit)
    public fun removeListener(listener: () -> Unit)
}

/** Adapts the service-owned player without creating another player. */
public class Media3PlaybackQueuePlayer(
    private val player: Player,
) : PlaybackQueuePlayerPort {
    private val listeners = mutableMapOf<() -> Unit, Player.Listener>()

    override val isPlaying: Boolean get() = player.isPlaying
    override val currentPositionMs: Long get() = player.currentPosition
    override val currentMediaItemId: String? get() = player.currentMediaItem?.mediaId

    override fun replaceQueue(
        items: List<MediaItem>,
        currentItemIndex: Int,
        positionMs: Long,
        resumePlayback: Boolean,
    ) {
        if (items.isEmpty()) {
            player.clearMediaItems()
        } else {
            player.setMediaItems(items, currentItemIndex, positionMs)
        }
        if (resumePlayback) player.play()
    }

    override fun addListener(listener: () -> Unit) {
        val media3Listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                listener()
            }
        }
        listeners[listener] = media3Listener
        player.addListener(media3Listener)
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)?.let(player::removeListener)
    }
}

/**
 * Observes verified Room-ready audio and changes the existing Media3 queue in place.
 * A playing item is never removed underneath playback; that update waits for a safe
 * boundary or for playback to stop.
 */
public class PlaybackQueueCoordinator(
    private val readyAudio: ReadyAudioRepository,
    private val player: PlaybackQueuePlayerPort,
    private val scope: CoroutineScope,
    private val mediaItemFactory: (VerifiedReadyAudio) -> MediaItem,
    private val onCatalogChanged: (PlaybackCatalog) -> Unit = {},
) : AutoCloseable {
    private var readyJob: Job? = null
    private var listener: (() -> Unit)? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var appliedSnapshot: QueueSnapshot? = null
    private var pendingSnapshot: QueueSnapshot? = null

    public fun start(projectId: String, chapters: List<ChapterEntity>) {
        closeObservation()
        pendingSnapshot = null
        this.chapters = chapters
        val playerListener: () -> Unit = { applyPendingIfSafe() }
        listener = playerListener
        player.addListener(playerListener)
        readyJob = scope.launch {
            readyAudio.observeVerified(projectId).collect(::update)
        }
    }

    /** Exposed for deterministic JVM tests and for an already observed Room emission. */
    public fun update(ready: List<VerifiedReadyAudio>) {
        val catalog = PlaybackCatalog.from(chapters, ready)
        val items = PlaybackCatalog.orderedReadyAudio(chapters, ready).map(mediaItemFactory)
        apply(QueueSnapshot(catalog, items))
    }

    override fun close() {
        closeObservation()
        pendingSnapshot = null
    }

    private fun closeObservation() {
        readyJob?.cancel()
        readyJob = null
        listener?.let(player::removeListener)
        listener = null
    }

    private fun applyPendingIfSafe() {
        pendingSnapshot?.let { apply(it) }
    }

    private fun apply(snapshot: QueueSnapshot) {
        val currentId = player.currentMediaItemId
        val applied = appliedSnapshot
        if (applied != null && sameItems(applied.items, snapshot.items)) {
            pendingSnapshot = null
            onCatalogChanged(snapshot.catalog)
            return
        }
        if (currentId != null && currentId !in snapshot.catalog.mediaItemIds && player.isPlaying) {
            pendingSnapshot = snapshot
            return
        }

        val preservesCurrent = currentId != null && currentId in snapshot.catalog.mediaItemIds
        val targetIndex = currentId
            ?.let(snapshot.catalog.mediaItemIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: 0
        val position = if (preservesCurrent) player.currentPositionMs.coerceAtLeast(0L) else 0L
        val resumePlayback = player.isPlaying
        appliedSnapshot = snapshot
        pendingSnapshot = null
        player.replaceQueue(snapshot.items, targetIndex, position, resumePlayback)
        onCatalogChanged(snapshot.catalog)
    }

    private fun sameItems(left: List<MediaItem>, right: List<MediaItem>): Boolean =
        left.map(::itemIdentity) == right.map(::itemIdentity)

    private fun itemIdentity(item: MediaItem): String =
        listOf(item.mediaId, item.localConfiguration?.uri?.toString()).joinToString("\u0000")

    private data class QueueSnapshot(
        val catalog: PlaybackCatalog,
        val items: List<MediaItem>,
    )
}
