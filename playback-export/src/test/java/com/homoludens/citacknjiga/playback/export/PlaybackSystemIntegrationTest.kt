package com.homoludens.citacknjiga.playback.export

import android.media.AudioManager
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class PlaybackSystemIntegrationTest {
    @Test
    public fun notificationMetadataUsesChapterBookAndAuthor() {
        val metadata = playbackItemMetadata(
            BookProjectEntity(
                id = "book",
                title = "Book title",
                author = "Author",
                sourceUri = "content://book",
                sourceFingerprint = "f",
                createdAt = 1L,
                updatedAt = 1L,
            ),
            ChapterEntity(
                id = "chapter",
                bookProjectId = "book",
                ordinal = 2,
                title = "Chapter title",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertEquals("Chapter title", metadata.title)
        assertEquals("Book title", metadata.bookTitle)
        assertEquals("Author", metadata.author)
        assertEquals("audiobook_playback", PlaybackNotificationConfiguration.CHANNEL_ID)
    }

    @Test
    public fun speechFocusPausesTransientLossAndResumesOnlyIfItWasPlaying() {
        val policy = PlaybackInterruptionPolicy()
        val paused = policy.onAudioFocusChange(
            PlaybackInterruptionState(),
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            isPlaying = true,
        )

        assertEquals(PlaybackInterruptionAction.PAUSE, paused.action)
        assertTrue(paused.state.resumeOnFocusGain)
        val resumed = policy.onAudioFocusChange(
            paused.state,
            AudioManager.AUDIOFOCUS_GAIN,
            isPlaying = false,
        )
        assertEquals(PlaybackInterruptionAction.RESUME, resumed.action)
        assertFalse(resumed.state.resumeOnFocusGain)
    }

    @Test
    public fun speechFocusPausesCanDuckAndPermanentLossDoesNotResume() {
        val policy = PlaybackInterruptionPolicy()
        val duckLoss = policy.onAudioFocusChange(
            PlaybackInterruptionState(),
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            isPlaying = true,
        )
        assertEquals(PlaybackInterruptionAction.PAUSE, duckLoss.action)

        val permanentLoss = policy.onAudioFocusChange(
            duckLoss.state,
            AudioManager.AUDIOFOCUS_LOSS,
            isPlaying = true,
        )
        assertEquals(PlaybackInterruptionAction.PAUSE, permanentLoss.action)
        assertFalse(permanentLoss.state.resumeOnFocusGain)
    }

    @Test
    public fun noisyOutputPausesAndClearsPendingResume() {
        val decision = PlaybackInterruptionPolicy().onNoisyOutput(
            PlaybackInterruptionState(resumeOnFocusGain = true),
            isPlaying = true,
        )

        assertEquals(PlaybackInterruptionAction.PAUSE, decision.action)
        assertEquals(PlaybackInterruptionState(), decision.state)
    }

    @Test
    public fun optionalDuckingRestoresVolumeOnFocusGain() {
        val policy = PlaybackInterruptionPolicy(pauseWhenDucked = false)
        val ducked = policy.onAudioFocusChange(
            PlaybackInterruptionState(),
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            isPlaying = true,
        )

        assertEquals(PlaybackInterruptionAction.DUCK, ducked.action)
        assertEquals(
            PlaybackInterruptionAction.RESTORE_VOLUME,
            policy.onAudioFocusChange(ducked.state, AudioManager.AUDIOFOCUS_GAIN, false).action,
        )
    }
}
