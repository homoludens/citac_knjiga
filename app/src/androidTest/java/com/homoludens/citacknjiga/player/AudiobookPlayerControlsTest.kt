package com.homoludens.citacknjiga.player

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.playback.export.PlayerControlState
import com.homoludens.citacknjiga.playback.export.PlaybackChapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

public class AudiobookPlayerControlsTest {
    @get:Rule
    public val composeRule = createComposeRule()

    @Test
    public fun controlsRenderAndUnavailableChapterCannotBeSelected() {
        var playPauseCount = 0
        composeRule.setContent {
            AudiobookPlayerControls(
                state = PlayerControlState(
                    connected = true,
                    currentChapterId = "ready",
                    durationMs = 10_000L,
                    chapters = listOf(
                        PlaybackChapter("ready", "Спремно", 0, listOf("segment"), 0, true),
                        PlaybackChapter("later", "Недоступно", 1, emptyList(), 1, false),
                    ),
                ),
                onPlayPause = { playPauseCount++ },
                onSeek = {},
                onPreviousChapter = { true },
                onNextChapter = { true },
                onJumpBackward = {},
                onJumpForward = {},
                onSelectChapter = { it == "ready" },
                onSetJumps = { _, _ -> },
                onSetSpeed = { true },
            )
        }

        composeRule.onNodeWithTag("player-controls").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.play)).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("chapter-picker").performClick()
        composeRule.onNodeWithText(context.getString(R.string.chapter_unavailable_format, "Недоступно"))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertEquals(1, playPauseCount)
    }
}
