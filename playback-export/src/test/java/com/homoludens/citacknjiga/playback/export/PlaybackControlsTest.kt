package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class PlaybackControlsTest {
    @Test
    public fun playPauseSeekAndJumpUseThePlayerCommands() {
        val player = FakePlayer(positionMs = 500L, durationMs = 10_000L)
        val commands = PlaybackControlCommands(player)

        commands.playPause()
        commands.seek(20_000L)
        commands.jumpBackward(PlaybackJumpValues.of(2_000L, 4_000L))
        commands.jumpForward(PlaybackJumpValues.of(2_000L, 4_000L))

        assertEquals(listOf("play", "seek:10000", "seek:8000", "seek:10000"), player.events)
    }

    @Test
    public fun seekAndJumpClampAtBothEnds() {
        val player = FakePlayer(positionMs = 500L, durationMs = 1_000L)
        val commands = PlaybackControlCommands(player)

        commands.seek(-1L)
        commands.jumpBackward(PlaybackJumpValues.of(15_000L, 30_000L))
        player.positionMs = 900L
        commands.jumpForward(PlaybackJumpValues.of(15_000L, 30_000L))

        assertEquals(listOf("seek:0", "seek:0", "seek:1000"), player.events)
        assertEquals(1_000L, clampSeek(50_000L, 1_000L))
        assertEquals(0L, clampSeek(-1L, null))
    }

    @Test
    public fun catalogOrdersSegmentsAndChapterCommandsSkipUnavailableChapters() {
        val chapters = listOf(
            chapter("unavailable", 0, ChapterStatus.PENDING),
            chapter("second", 1, ChapterStatus.READY),
            chapter("first", 2, ChapterStatus.READY),
        )
        val catalog = PlaybackCatalog.from(
            chapters,
            listOf(
                audio("first-segment", "first", 0),
                audio("second-segment", "second", 0),
            ),
        )
        val player = FakePlayer(currentMediaItemIndex = 0, mediaItemCount = 2)
        val commands = PlaybackControlCommands(player)

        assertEquals(listOf("second-segment", "first-segment"), catalog.mediaItemIds)
        assertEquals("second", catalog.chapterForMediaItemIndex(0)?.id)
        assertFalse(commands.selectChapter(catalog, "unavailable"))
        assertTrue(commands.nextChapter(catalog))
        assertEquals(listOf("seekItem:1:0", "play"), player.events)
    }

    @Test
    public fun speedAcceptsOnlyTheSupportedValuesAndJumpsAreConfigurable() {
        val player = FakePlayer()
        val commands = PlaybackControlCommands(player)

        assertTrue(commands.setSpeed(1.5f))
        assertFalse(commands.setSpeed(1.1f))
        assertEquals(1.5f, player.speed)
        assertEquals(PlaybackJumpValues(1_000L, 120_000L), PlaybackJumpValues.of(-5L, 200_000L))
    }

    private fun chapter(id: String, ordinal: Int, status: ChapterStatus) = ChapterEntity(
        id = id,
        bookProjectId = "book",
        ordinal = ordinal,
        title = id,
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun audio(id: String, chapterId: String, sequence: Int) = VerifiedReadyAudio(
        segment = AudioSegmentEntity(
            id = id,
            chapterId = chapterId,
            narrationBlockId = "block-$id",
            sequence = sequence,
            chunkOrdinal = 0,
            createdAt = 1L,
            updatedAt = 1L,
        ),
        file = File("$id.wav"),
    )

    private class FakePlayer(
        override var positionMs: Long = 0L,
        override val durationMs: Long? = null,
        override var currentMediaItemIndex: Int = 0,
        override val mediaItemCount: Int = 0,
    ) : PlaybackPlayerPort {
        override var isPlaying: Boolean = false
        override var currentMediaItemId: String? = null
        private var speedValue: Float = 1.0f
        override val speed: Float get() = speedValue
        val events = mutableListOf<String>()

        override fun play() {
            isPlaying = true
            events += "play"
        }

        override fun pause() {
            isPlaying = false
            events += "pause"
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs
            events += "seek:$positionMs"
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            currentMediaItemIndex = mediaItemIndex
            this.positionMs = positionMs
            events += "seekItem:$mediaItemIndex:$positionMs"
        }

        override fun setSpeed(speed: Float) {
            speedValue = speed
        }

        override fun addListener(listener: () -> Unit) = Unit

        override fun removeListener(listener: () -> Unit) = Unit
    }
}
