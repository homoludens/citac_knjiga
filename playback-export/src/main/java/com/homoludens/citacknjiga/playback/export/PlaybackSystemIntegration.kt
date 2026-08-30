@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.homoludens.citacknjiga.playback.export

import android.media.AudioManager
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity

public object PlaybackNotificationConfiguration {
    public const val CHANNEL_ID: String = "audiobook_playback"
    public const val NOTIFICATION_ID: Int = 4101

    public fun provider(context: android.content.Context): DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider.Builder(context)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .build()
            .apply { setSmallIcon(android.R.drawable.ic_media_play) }
}

public data class PlaybackItemMetadata(
    public val title: String,
    public val bookTitle: String,
    public val author: String?,
)

public fun playbackItemMetadata(
    book: BookProjectEntity,
    chapter: ChapterEntity?,
): PlaybackItemMetadata = PlaybackItemMetadata(
    title = chapter?.title?.takeIf(String::isNotBlank) ?: "Chapter ${(chapter?.ordinal ?: 0) + 1}",
    bookTitle = book.title.takeIf(String::isNotBlank) ?: "Audiobook",
    author = book.author?.takeIf(String::isNotBlank),
)

public fun PlaybackItemMetadata.toMediaMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setAlbumTitle(bookTitle)
    .setArtist(author)
    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
    .setIsPlayable(true)
    .build()

public object AudiobookMediaButtons {
    public val preferences: List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Back 15 seconds")
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Forward 30 seconds")
            .build(),
    )
}

/** Keeps standard system controllers on the player's available commands. */
public class AudiobookMediaSessionCallback : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.accept(
        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
        session.player.availableCommands,
    )
}

public enum class PlaybackInterruptionAction {
    NONE,
    PAUSE,
    DUCK,
    RESUME,
    RESTORE_VOLUME,
}

public data class PlaybackInterruptionState(
    public val resumeOnFocusGain: Boolean = false,
    public val restoreVolumeOnFocusGain: Boolean = false,
)

public data class PlaybackInterruptionDecision(
    public val action: PlaybackInterruptionAction,
    public val state: PlaybackInterruptionState,
)

/**
 * Testable policy for the platform focus callbacks handled internally by Media3.
 * Speech content deliberately pauses when another app requests ducking so words
 * are not lost; a non-speech configuration can opt into volume ducking.
 */
public class PlaybackInterruptionPolicy(
    private val pauseWhenDucked: Boolean = true,
) {
    public fun onAudioFocusChange(
        state: PlaybackInterruptionState,
        focusChange: Int,
        isPlaying: Boolean,
    ): PlaybackInterruptionDecision = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> when {
            state.resumeOnFocusGain -> PlaybackInterruptionDecision(
                PlaybackInterruptionAction.RESUME,
                PlaybackInterruptionState(),
            )
            state.restoreVolumeOnFocusGain -> PlaybackInterruptionDecision(
                PlaybackInterruptionAction.RESTORE_VOLUME,
                PlaybackInterruptionState(),
            )
            else -> PlaybackInterruptionDecision(PlaybackInterruptionAction.NONE, state)
        }
        AudioManager.AUDIOFOCUS_LOSS -> PlaybackInterruptionDecision(
            if (isPlaying) PlaybackInterruptionAction.PAUSE else PlaybackInterruptionAction.NONE,
            PlaybackInterruptionState(),
        )
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientLoss(isPlaying)
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (pauseWhenDucked) {
            transientLoss(isPlaying)
        } else if (isPlaying) {
            PlaybackInterruptionDecision(
                PlaybackInterruptionAction.DUCK,
                PlaybackInterruptionState(restoreVolumeOnFocusGain = true),
            )
        } else {
            PlaybackInterruptionDecision(PlaybackInterruptionAction.NONE, state)
        }
        else -> PlaybackInterruptionDecision(PlaybackInterruptionAction.NONE, state)
    }

    public fun onNoisyOutput(
        state: PlaybackInterruptionState,
        isPlaying: Boolean,
    ): PlaybackInterruptionDecision = PlaybackInterruptionDecision(
        if (isPlaying) PlaybackInterruptionAction.PAUSE else PlaybackInterruptionAction.NONE,
        PlaybackInterruptionState(),
    )

    private fun transientLoss(isPlaying: Boolean): PlaybackInterruptionDecision = PlaybackInterruptionDecision(
        if (isPlaying) PlaybackInterruptionAction.PAUSE else PlaybackInterruptionAction.NONE,
        PlaybackInterruptionState(resumeOnFocusGain = isPlaying),
    )
}
