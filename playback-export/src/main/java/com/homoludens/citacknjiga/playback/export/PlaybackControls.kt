package com.homoludens.citacknjiga.playback.export

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus

public data class PlaybackChapter(
    public val id: String,
    public val title: String,
    public val ordinal: Int,
    public val mediaItemIds: List<String>,
    public val firstMediaItemIndex: Int,
    public val available: Boolean,
)

public data class PlaybackCatalog(
    public val chapters: List<PlaybackChapter>,
    public val mediaItemIds: List<String>,
) {
    public fun chapterForMediaItemIndex(index: Int): PlaybackChapter? =
        chapters.firstOrNull { index in it.firstMediaItemIndex until it.firstMediaItemIndex + it.mediaItemIds.size }

    public fun chapterForMediaItemId(id: String?): PlaybackChapter? =
        id?.let { mediaItemId -> chapters.firstOrNull { mediaItemId in it.mediaItemIds } }

    public companion object {
        public fun from(
            chapters: List<ChapterEntity>,
            readyAudio: List<VerifiedReadyAudio>,
        ): PlaybackCatalog {
            val orderedAudio = orderedReadyAudio(chapters, readyAudio)
            val ids = orderedAudio.map { it.segment.id }
            var mediaItemIndex = 0
            val playbackChapters = chapters.sortedBy { it.ordinal }.map { chapter ->
                val chapterIds = orderedAudio
                    .filter { it.segment.chapterId == chapter.id }
                    .map { it.segment.id }
                PlaybackChapter(
                    id = chapter.id,
                    title = chapter.title,
                    ordinal = chapter.ordinal,
                    mediaItemIds = chapterIds,
                    firstMediaItemIndex = mediaItemIndex,
                    available = chapter.status == ChapterStatus.READY && chapterIds.isNotEmpty(),
                ).also { mediaItemIndex += chapterIds.size }
            }
            return PlaybackCatalog(playbackChapters, ids)
        }

        public fun orderedReadyAudio(
            chapters: List<ChapterEntity>,
            readyAudio: List<VerifiedReadyAudio>,
        ): List<VerifiedReadyAudio> {
            val chapterOrder = chapters.associateBy { it.id }
            // A duplicate ID must select the same artifact regardless of Room emission order.
            return readyAudio.sortedWith(
                compareBy<VerifiedReadyAudio>({ chapterOrder[it.segment.chapterId]?.ordinal ?: Int.MAX_VALUE })
                    .thenBy { it.segment.chapterId }
                    .thenBy { it.segment.sequence }
                    .thenBy { it.segment.id }
                    .thenBy { it.file.path },
            ).distinctBy { it.segment.id }
        }
    }
}

public data class PlaybackJumpValues(
    public val backwardMs: Long = 15_000L,
    public val forwardMs: Long = 30_000L,
) {
    public companion object {
        public const val MINIMUM_MS: Long = 1_000L
        public const val MAXIMUM_MS: Long = 120_000L

        public fun of(backwardMs: Long, forwardMs: Long): PlaybackJumpValues = PlaybackJumpValues(
            backwardMs = backwardMs.coerceIn(MINIMUM_MS, MAXIMUM_MS),
            forwardMs = forwardMs.coerceIn(MINIMUM_MS, MAXIMUM_MS),
        )
    }
}

public val SUPPORTED_PLAYBACK_SPEEDS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

public interface PlaybackPlayerPort {
    public val isPlaying: Boolean
    public val positionMs: Long
    public val durationMs: Long?
    public val currentMediaItemId: String?
    public val currentMediaItemIndex: Int
    public val mediaItemCount: Int
    public val speed: Float

    public fun play()
    public fun pause()
    public fun seekTo(positionMs: Long)
    public fun seekTo(mediaItemIndex: Int, positionMs: Long)
    public fun setSpeed(speed: Float)
    public fun addListener(listener: () -> Unit)
    public fun removeListener(listener: () -> Unit)
}

/** Pure command layer used by the Media3 controller and JVM tests. */
public class PlaybackControlCommands(
    private val player: PlaybackPlayerPort,
) {
    public fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    public fun seek(positionMs: Long) {
        player.seekTo(clampSeek(positionMs, player.durationMs))
    }

    public fun jumpBackward(values: PlaybackJumpValues) {
        seek(player.positionMs - values.backwardMs)
    }

    public fun jumpForward(values: PlaybackJumpValues) {
        seek(player.positionMs + values.forwardMs)
    }

    public fun selectChapter(catalog: PlaybackCatalog, chapterId: String): Boolean {
        val chapter = catalog.chapters.firstOrNull { it.id == chapterId && it.available } ?: return false
        if (chapter.firstMediaItemIndex >= player.mediaItemCount) return false
        player.seekTo(chapter.firstMediaItemIndex, 0L)
        player.play()
        return true
    }

    public fun previousChapter(catalog: PlaybackCatalog): Boolean = moveChapter(catalog, -1)

    public fun nextChapter(catalog: PlaybackCatalog): Boolean = moveChapter(catalog, 1)

    public fun setSpeed(speed: Float): Boolean {
        if (speed !in SUPPORTED_PLAYBACK_SPEEDS) return false
        player.setSpeed(speed)
        return true
    }

    private fun moveChapter(catalog: PlaybackCatalog, direction: Int): Boolean {
        val current = catalog.chapterForMediaItemIndex(player.currentMediaItemIndex)
        val target = catalog.chapters
            .asSequence()
            .filter { it.available }
            .let { available ->
                if (current == null) {
                    available.firstOrNull()
                } else if (direction < 0) {
                    available.lastOrNull { it.ordinal < current.ordinal }
                } else {
                    available.firstOrNull { it.ordinal > current.ordinal }
                }
            }
            ?: return false
        return selectChapter(catalog, target.id)
    }
}

public fun clampSeek(positionMs: Long, durationMs: Long?): Long {
    val nonNegative = positionMs.coerceAtLeast(0L)
    return durationMs?.takeIf { it >= 0L }?.let(nonNegative::coerceAtMost) ?: nonNegative
}

/** Adapter around the MediaController; the service remains the ExoPlayer owner. */
public class Media3PlayerPort(
    private val player: Player,
) : PlaybackPlayerPort {
    private val listeners = mutableMapOf<() -> Unit, Player.Listener>()

    override val isPlaying: Boolean get() = player.isPlaying
    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long? get() = player.duration.takeIf { it >= 0L }
    override val currentMediaItemId: String? get() = player.currentMediaItem?.mediaId
    override val currentMediaItemIndex: Int get() = player.currentMediaItemIndex
    override val mediaItemCount: Int get() = player.mediaItemCount
    override val speed: Float get() = player.playbackParameters.speed

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        player.seekTo(mediaItemIndex, positionMs)
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed))
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

    public fun close() {
        listeners.values.forEach(player::removeListener)
        listeners.clear()
    }
}
