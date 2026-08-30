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
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    var selectedChapterMenu by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth().testTag("player-controls")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                state.currentChapterId?.let { id -> state.chapters.firstOrNull { it.id == id }?.title }
                    ?: "Нема активног поглавља",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = state.positionMs.toFloat().coerceIn(0f, state.durationMs?.coerceAtLeast(0L)?.toFloat() ?: 0f),
                onValueChange = { onSeek(it.toLong()) },
                enabled = state.durationMs?.let { it > 0L } == true,
                valueRange = 0f..(state.durationMs?.coerceAtLeast(1L)?.toFloat() ?: 1f),
                modifier = Modifier.testTag("player-seek"),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { onPreviousChapter() }) { Text("Претходно") }
                Button(onClick = onPlayPause) { Text(if (state.playing) "Пауза" else "Пусти") }
                OutlinedButton(onClick = { onNextChapter() }) { Text("Следеће") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = onJumpBackward) { Text("-${formatJump(state.jumps.backwardMs)}") }
                OutlinedButton(onClick = onJumpForward) { Text("+${formatJump(state.jumps.forwardMs)}") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { selectedChapterMenu = true },
                    modifier = Modifier.testTag("chapter-picker"),
                ) {
                    Text(state.currentChapterId?.let { id ->
                        state.chapters.firstOrNull { it.id == id }?.title ?: "Изабери поглавље"
                    } ?: "Изабери поглавље")
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
                }) { Text("Назад ${formatJump(state.jumps.backwardMs)}") }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    onSetJumps(state.jumps.backwardMs, nextValue(FORWARD_JUMPS, state.jumps.forwardMs))
                }) { Text("Напред ${formatJump(state.jumps.forwardMs)}") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Брзина:")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SUPPORTED_PLAYBACK_SPEEDS.forEach { speed ->
                    OutlinedButton(
                        onClick = { onSetSpeed(speed) },
                        enabled = state.speed != speed,
                    ) { Text(speedLabel(speed)) }
                }
                }
            }
        }
    }
}

private fun chapterLabel(chapter: PlaybackChapter): String =
    chapter.title + if (chapter.available) "" else " (није спремно)"

private fun nextValue(values: List<Long>, current: Long): Long =
    values[(values.indexOf(current).takeIf { it >= 0 }?.plus(1) ?: 0) % values.size]

private fun formatJump(milliseconds: Long): String = "${milliseconds / 1_000}s"

private fun speedLabel(speed: Float): String = "${speed}x"
