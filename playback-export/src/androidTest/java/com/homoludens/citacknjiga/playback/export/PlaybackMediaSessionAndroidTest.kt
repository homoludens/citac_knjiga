package com.homoludens.citacknjiga.playback.export

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class PlaybackMediaSessionAndroidTest {
    @Test
    public fun mediaSessionExposesStandardControllerCommandsAndMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var title: CharSequence? = null
        var albumTitle: CharSequence? = null
        var artist: CharSequence? = null
        var hasPlayPause = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            val session = MediaSession.Builder(context, player)
                .setId("test_audiobook_session")
                .setCallback(AudiobookMediaSessionCallback())
                .setMediaButtonPreferences(AudiobookMediaButtons.preferences)
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId("segment")
                .setUri(android.net.Uri.parse("file:///dev/null"))
                .setMediaMetadata(PlaybackItemMetadata("Chapter", "Book", "Author").toMediaMetadata())
                .build()
            player.setMediaItem(mediaItem)
            title = player.mediaMetadata.title
            albumTitle = player.mediaMetadata.albumTitle
            artist = player.mediaMetadata.artist
            hasPlayPause = session.player.isCommandAvailable(androidx.media3.common.Player.COMMAND_PLAY_PAUSE)
            session.release()
            player.release()
        }

        assertEquals("Chapter", title)
        assertEquals("Book", albumTitle)
        assertEquals("Author", artist)
        assertEquals(2, AudiobookMediaButtons.preferences.size)
        assertEquals(
            androidx.media3.common.Player.COMMAND_SEEK_BACK,
            AudiobookMediaButtons.preferences[0].playerCommand,
        )
        assertEquals(
            androidx.media3.common.Player.COMMAND_SEEK_FORWARD,
            AudiobookMediaButtons.preferences[1].playerCommand,
        )
        assertTrue(hasPlayPause)
    }

    @Test
    public fun mediaSessionServiceCanBeConnectedAndReleased() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controllerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java)),
        ).buildAsync()
        val controller = controllerFuture.get(5, TimeUnit.SECONDS)
        var hasPlayPause = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            hasPlayPause = controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_PLAY_PAUSE)
            controller.release()
        }

        assertTrue(hasPlayPause)

        controllerFuture.cancel(false)
    }

    @Test
    public fun queueAdapterPreservesCurrentItemAndPositionAcrossAnOrderedUpdate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var currentId: String? = null
        var positionMs = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            val queue = Media3PlaybackQueuePlayer(player)
            queue.replaceQueue(
                listOf(mediaItem("active"), mediaItem("after")),
                currentItemIndex = 0,
                positionMs = 321L,
                resumePlayback = false,
            )
            queue.replaceQueue(
                listOf(mediaItem("before"), mediaItem("active"), mediaItem("after")),
                currentItemIndex = 1,
                positionMs = 321L,
                resumePlayback = false,
            )
            currentId = player.currentMediaItem?.mediaId
            positionMs = player.currentPosition
            player.release()
        }

        assertEquals("active", currentId)
        assertEquals(321L, positionMs)
    }

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(android.net.Uri.parse("file:///data/local/tmp/$id.m4a"))
        .build()
}
