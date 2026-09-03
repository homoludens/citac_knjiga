package com.homoludens.citacknjiga.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.playback.export.PlayerControlState
import com.homoludens.citacknjiga.playback.export.PlaybackChapter
import com.homoludens.citacknjiga.playback.export.SUPPORTED_PLAYBACK_SPEEDS
import com.homoludens.citacknjiga.library.BookCover
import com.homoludens.citacknjiga.ui.theme.CitacKnjigaTheme

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
    title: String? = null,
    author: String? = null,
    coverPath: String? = null,
    expanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var selectedChapterMenu by remember { mutableStateOf(false) }
    val backwardJump = formatJump(state.jumps.backwardMs)
    val forwardJump = formatJump(state.jumps.forwardMs)
    val seekDescription = stringResource(R.string.seek_position_description)
    val backwardDescription = stringResource(R.string.seek_backward_description, backwardJump)
    val forwardDescription = stringResource(R.string.seek_forward_description, forwardJump)
    val previousDescription = stringResource(R.string.previous_chapter)
    val nextDescription = stringResource(R.string.next_chapter)
    val hasPlayback = state.durationMs != null || state.chapters.isNotEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = (if (expanded) modifier.fillMaxSize() else modifier.fillMaxWidth()).testTag("player-controls"),
    ) {
        Column(
            modifier = (if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (expanded && (title != null || coverPath != null)) {
                BookCover(
                    path = coverPath,
                    title = title ?: stringResource(R.string.book_fallback),
                    modifier = Modifier.size(width = 164.dp, height = 220.dp),
                )
            }
            Text(
                title?.ifBlank { null } ?: stringResource(R.string.no_active_book),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (!author.isNullOrBlank()) {
                Text(author, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                Text(formatPosition(state.positionMs), style = MaterialTheme.typography.labelMedium)
                Text(
                    "-${formatPosition(((state.durationMs ?: 0L) - state.positionMs).coerceAtLeast(0L))}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onPreviousChapter() },
                    enabled = state.chapters.isNotEmpty(),
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = previousDescription
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                }
                TextButton(
                    onClick = onJumpBackward,
                    enabled = hasPlayback,
                    modifier = Modifier.weight(1f).semantics { contentDescription = backwardDescription },
                ) { Text("-$backwardJump") }
                Button(onClick = onPlayPause, enabled = hasPlayback) {
                    Text(stringResource(if (state.playing) R.string.pause else R.string.play))
                }
                TextButton(
                    onClick = onJumpForward,
                    enabled = hasPlayback,
                    modifier = Modifier.weight(1f).semantics { contentDescription = forwardDescription },
                ) { Text("+$forwardJump") }
                TextButton(
                    onClick = { onNextChapter() },
                    enabled = state.chapters.isNotEmpty(),
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = nextDescription
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.speed), style = MaterialTheme.typography.labelMedium)
                Text(speedLabel(state.speed), color = MaterialTheme.colorScheme.primary)
                Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { selectedChapterMenu = true },
                        enabled = state.chapters.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("chapter-picker"),
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
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = hasPlayback, onClick = {
                    onSetJumps(nextValue(BACKWARD_JUMPS, state.jumps.backwardMs), state.jumps.forwardMs)
                }) { Text(stringResource(R.string.jump_backward_format, backwardJump)) }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = hasPlayback, onClick = {
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
                        enabled = hasPlayback && state.speed != speed,
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

private fun formatPosition(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

@Preview(showBackground = true)
@Composable
private fun PlayerPreview() {
    CitacKnjigaTheme {
        AudiobookPlayerControls(
            state = PlayerControlState(durationMs = 24 * 60_000L, positionMs = 8 * 60_000L),
            title = "На Дрини ћуприја",
            author = "Иво Андрић",
            onPlayPause = {},
            onSeek = {},
            onPreviousChapter = { true },
            onNextChapter = { true },
            onJumpBackward = {},
            onJumpForward = {},
            onSelectChapter = { true },
            onSetJumps = { _, _ -> },
            onSetSpeed = { true },
        )
    }
}
