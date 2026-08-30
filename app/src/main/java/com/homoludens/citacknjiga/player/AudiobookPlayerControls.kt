package com.homoludens.citacknjiga.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.playback.export.PlayerControlState
import com.homoludens.citacknjiga.playback.export.PlaybackChapter
import com.homoludens.citacknjiga.playback.export.SUPPORTED_PLAYBACK_SPEEDS

private val BACKWARD_JUMPS = listOf(5_000L, 15_000L, 30_000L)
private val FORWARD_JUMPS = listOf(15_000L, 30_000L, 60_000L)

@Composable
public fun AudiobookPlayerControls(
    state: PlayerControlState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviousChapter: () -> Boolean,
    onNextChapter: () -> Boolean,
    onJumpBackward: () -> Unit,
    onJumpForward: () -> Unit,
    onSelectChapter: (String) -> Boolean,
    onSetJumps: (Long, Long) -> Unit,
    onSetSpeed: (Float) -> Boolean,
    onRegenerate: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    var selectedChapterMenu by remember { mutableStateOf(false) }
    val backwardJump = formatJump(state.jumps.backwardMs)
    val forwardJump = formatJump(state.jumps.forwardMs)
    val seekDescription = stringResource(R.string.seek_position_description)
    val backwardDescription = stringResource(R.string.seek_backward_description, backwardJump)
    val forwardDescription = stringResource(R.string.seek_forward_description, forwardJump)
    Card(modifier = modifier.fillMaxWidth().testTag("player-controls")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                state.currentChapterId?.let { id -> state.chapters.firstOrNull { it.id == id }?.title }
                    ?: stringResource(R.string.no_active_chapter),
                style = MaterialTheme.typography.titleMedium,
            )
            state.unavailableAudio.forEach { unavailable ->
                Text(unavailable.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { onRegenerate(unavailable.segment.id) }) {
                    Text(stringResource(R.string.player_retry))
                }
            }
            Slider(
                value = state.positionMs.toFloat().coerceIn(0f, state.durationMs?.coerceAtLeast(0L)?.toFloat() ?: 0f),
                onValueChange = { onSeek(it.toLong()) },
                enabled = state.durationMs?.let { it > 0L } == true,
                valueRange = 0f..(state.durationMs?.coerceAtLeast(1L)?.toFloat() ?: 1f),
                modifier = Modifier
                    .testTag("player-seek")
                    .semantics { contentDescription = seekDescription },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { onPreviousChapter() }) { Text(stringResource(R.string.previous_chapter)) }
                Button(onClick = onPlayPause) { Text(stringResource(if (state.playing) R.string.pause else R.string.play)) }
                OutlinedButton(onClick = { onNextChapter() }) { Text(stringResource(R.string.next_chapter)) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(
                    onClick = onJumpBackward,
                    modifier = Modifier.semantics { contentDescription = backwardDescription },
                ) { Text("-$backwardJump") }
                OutlinedButton(
                    onClick = onJumpForward,
                    modifier = Modifier.semantics { contentDescription = forwardDescription },
                ) { Text("+$forwardJump") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { selectedChapterMenu = true },
                    modifier = Modifier.testTag("chapter-picker"),
                ) {
                    Text(state.currentChapterId?.let { id ->
                        state.chapters.firstOrNull { it.id == id }?.title ?: stringResource(R.string.choose_chapter)
                    } ?: stringResource(R.string.choose_chapter))
                }
                DropdownMenu(
                    expanded = selectedChapterMenu,
                    onDismissRequest = { selectedChapterMenu = false },
                ) {
                    state.chapters.forEach { chapter ->
                        DropdownMenuItem(
                            text = { Text(chapterLabel(chapter)) },
                            enabled = chapter.available,
                            onClick = {
                                if (onSelectChapter(chapter.id)) selectedChapterMenu = false
                            },
                        )
                    }
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    onSetJumps(nextValue(BACKWARD_JUMPS, state.jumps.backwardMs), state.jumps.forwardMs)
                }) { Text(stringResource(R.string.jump_backward_format, backwardJump)) }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    onSetJumps(state.jumps.backwardMs, nextValue(FORWARD_JUMPS, state.jumps.forwardMs))
                }) { Text(stringResource(R.string.jump_forward_format, forwardJump)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.speed))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SUPPORTED_PLAYBACK_SPEEDS.forEach { speed ->
                    val speedDescription = stringResource(
                        R.string.speed_description_format,
                        speedLabel(speed),
                    )
                    OutlinedButton(
                        modifier = Modifier.semantics { contentDescription = speedDescription },
                        onClick = { onSetSpeed(speed) },
                        enabled = state.speed != speed,
                    ) { Text(speedLabel(speed)) }
                }
                }
            }
        }
    }
}

@Composable
private fun chapterLabel(chapter: PlaybackChapter): String =
    if (chapter.available) chapter.title else stringResource(R.string.chapter_unavailable_format, chapter.title)

private fun nextValue(values: List<Long>, current: Long): Long =
    values[(values.indexOf(current).takeIf { it >= 0 }?.plus(1) ?: 0) % values.size]

private fun formatJump(milliseconds: Long): String = "${milliseconds / 1_000}s"

private fun speedLabel(speed: Float): String = "${speed}x"
