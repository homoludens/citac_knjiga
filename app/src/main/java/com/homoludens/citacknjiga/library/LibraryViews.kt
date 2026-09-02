package com.homoludens.citacknjiga.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus

public enum class GenerationAction {
    PAUSE,
    RESUME,
    CANCEL,
    RETRY,
}

@Composable
public fun LibraryScreen(
    state: LibraryViewState,
    onBookClick: (String) -> Unit,
    onGenerationAction: (String, GenerationAction) -> Unit = { _, _ -> },
    onDeleteBook: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineSmall)
        if (state.books.isEmpty()) {
            Text(stringResource(R.string.no_books))
        } else {
            state.books.forEach { book ->
                LibraryBookCard(
                    book,
                    onClick = { onBookClick(book.project.id) },
                    onGenerationAction = onGenerationAction,
                    onDeleteBook = onDeleteBook,
                )
            }
        }
    }
}

@Composable
private fun LibraryBookCard(
    book: LibraryBookDisplay,
    onClick: () -> Unit,
    onGenerationAction: (String, GenerationAction) -> Unit,
    onDeleteBook: (String) -> Unit,
) {
    val title = book.title.ifBlank { stringResource(R.string.book_fallback) }
    val author = book.author.ifBlank { stringResource(R.string.author_fallback) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BookCover(book.coverPath, title, Modifier.size(64.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(author, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.chapters_ready_format, book.readyChapterCount, book.chapters.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (book.hasGenerationWork) {
                    GenerationProgress(book, onGenerationAction)
                }
                Text(stringResource(R.string.storage_format, formatBytes(book.storageBytes)), style = MaterialTheme.typography.labelSmall)
                book.listeningProgress?.let { listening ->
                    Text(
                        stringResource(
                            R.string.listening_format,
                            listening.chapterTitle ?: stringResource(R.string.book_fallback),
                            formatDuration(listening.positionMs),
                        ),
                    )
                }
                if (book.failures.isNotEmpty()) {
                    Text(
                        stringResource(R.string.errors_count_format, book.failures.size),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    Text(
                        safeFailureMessage(book.failures.first()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                DeleteBookAction(book, onDeleteBook)
            }
        }
    }
}

@Composable
public fun BookDetailScreen(
    book: LibraryBookDisplay?,
    onGenerationAction: (String, GenerationAction) -> Unit = { _, _ -> },
    onDeleteBook: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (book == null) {
        Text(stringResource(R.string.book_not_found), modifier = modifier.padding(24.dp))
        return
    }
    val title = book.title.ifBlank { stringResource(R.string.book_fallback) }
    val author = book.author.ifBlank { stringResource(R.string.author_fallback) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            BookCover(book.coverPath, title, Modifier.size(104.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(author)
                Text(
                    stringResource(R.string.status_format, book.status.displayName()),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.storage_format, formatBytes(book.storageBytes)))
            }
        }
        if (book.hasGenerationWork) GenerationProgress(book, onGenerationAction)
        book.listeningProgress?.let { listening ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.listening_progress), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${listening.chapterTitle ?: stringResource(R.string.chapter_fallback)}: ${formatDuration(listening.positionMs)}",
                    )
                    listening.fraction?.let { fraction ->
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                                },
                        )
                    }
                }
            }
        }
        Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleLarge)
        book.chapters.forEach { chapter ->
            ChapterRow(chapter)
        }
        if (book.failures.isNotEmpty()) {
            Text(stringResource(R.string.generation_errors), style = MaterialTheme.typography.titleLarge)
            book.failures.forEach { failure ->
                Text(
                    safeFailureMessage(failure),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
        DeleteBookAction(book, onDeleteBook)
    }
}

@Composable
private fun DeleteBookAction(book: LibraryBookDisplay, onDeleteBook: (String) -> Unit) {
    var showConfirmation by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showConfirmation = true },
        modifier = Modifier.fillMaxWidth().testTag("delete-book-${book.project.id}"),
    ) {
        Text(stringResource(R.string.delete_book))
    }
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.delete_book_title)) },
            text = { Text(stringResource(R.string.delete_book_message, book.title.ifBlank { stringResource(R.string.book_fallback) })) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        onDeleteBook(book.project.id)
                    },
                    modifier = Modifier.testTag("confirm-delete-book-${book.project.id}"),
                ) { Text(stringResource(R.string.delete_book_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun GenerationProgress(
    book: LibraryBookDisplay,
    onGenerationAction: (String, GenerationAction) -> Unit,
) {
    val runId = book.generationRunId
    val status = book.generationStatus
    val progress = stringResource(
        R.string.generation_progress_format,
        book.generationProgress.completed,
        book.generationProgress.total,
    )
    val statusDescription = generationStateDescription(status)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            progress,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusDescription
            },
        )
        LinearProgressIndicator(
            progress = { book.generationProgress.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(book.generationProgress.fraction, 0f..1f)
                    stateDescription = progress
                },
        )
        if (runId != null) {
            when (status) {
                GenerationRunStatus.RUNNING -> {
                    Text(stringResource(R.string.generation_running))
                    GenerationActionButton(stringResource(R.string.generation_pause)) {
                        onGenerationAction(runId, GenerationAction.PAUSE)
                    }
                    GenerationActionButton(stringResource(R.string.generation_cancel)) {
                        onGenerationAction(runId, GenerationAction.CANCEL)
                    }
                }
                GenerationRunStatus.PAUSED -> {
                    Text(stringResource(R.string.generation_paused))
                    GenerationActionButton(stringResource(R.string.generation_resume)) {
                        onGenerationAction(runId, GenerationAction.RESUME)
                    }
                    GenerationActionButton(stringResource(R.string.generation_cancel)) {
                        onGenerationAction(runId, GenerationAction.CANCEL)
                    }
                }
                GenerationRunStatus.QUEUED -> {
                    Text(stringResource(R.string.generation_queued))
                    GenerationActionButton(stringResource(R.string.generation_cancel)) {
                        onGenerationAction(runId, GenerationAction.CANCEL)
                    }
                }
                GenerationRunStatus.FAILED -> {
                    Text(stringResource(R.string.generation_failed_action), color = MaterialTheme.colorScheme.error)
                    GenerationActionButton(stringResource(R.string.generation_retry)) {
                        onGenerationAction(runId, GenerationAction.RETRY)
                    }
                }
                GenerationRunStatus.COMPLETED -> Text(stringResource(R.string.generation_completed))
                GenerationRunStatus.CANCELLED -> Unit
                null -> Unit
            }
        }
    }
}

@Composable
private fun GenerationActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun ChapterRow(chapter: ChapterDisplay) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${chapter.chapter.ordinal + 1}. ${chapter.chapter.title}", modifier = Modifier.weight(1f))
            Text(chapter.chapter.status.displayName(), style = MaterialTheme.typography.labelMedium)
        }
        if (chapter.progress.total > 0) {
            LinearProgressIndicator(progress = { chapter.progress.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(
                    R.string.audio_progress_format,
                    chapter.progress.completed,
                    chapter.progress.total,
                    formatDuration(chapter.durationMs),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(stringResource(R.string.storage_format, formatBytes(chapter.storageBytes)), style = MaterialTheme.typography.labelSmall)
        chapter.chapter.lastError?.let {
            Text(
                safeFailureMessage(it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun BookCover(path: String?, title: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = stringResource(R.string.cover_description_format, title),
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.book_fallback), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BookProjectStatus.displayName(): String = when (this) {
    BookProjectStatus.IMPORTING -> stringResource(R.string.status_importing)
    BookProjectStatus.READY -> stringResource(R.string.status_ready)
    BookProjectStatus.GENERATING -> stringResource(R.string.status_generating)
    BookProjectStatus.PAUSED -> stringResource(R.string.status_paused)
    BookProjectStatus.COMPLETED -> stringResource(R.string.status_completed)
    BookProjectStatus.FAILED -> stringResource(R.string.status_failed)
}

@Composable
private fun ChapterStatus.displayName(): String = when (this) {
    ChapterStatus.PENDING -> stringResource(R.string.status_pending)
    ChapterStatus.GENERATING -> stringResource(R.string.status_generating)
    ChapterStatus.PARTIAL -> stringResource(R.string.status_partial)
    ChapterStatus.READY -> stringResource(R.string.status_ready)
    ChapterStatus.FAILED -> stringResource(R.string.status_failed)
}

@Composable
private fun generationStateDescription(status: GenerationRunStatus?): String = when (status) {
    GenerationRunStatus.RUNNING -> stringResource(R.string.generation_running)
    GenerationRunStatus.PAUSED -> stringResource(R.string.generation_paused)
    GenerationRunStatus.QUEUED -> stringResource(R.string.generation_queued)
    GenerationRunStatus.COMPLETED -> stringResource(R.string.generation_completed)
    GenerationRunStatus.FAILED -> stringResource(R.string.generation_failed_action)
    GenerationRunStatus.CANCELLED, null -> ""
}

@Composable
private fun safeFailureMessage(value: String): String = when (value.substringBefore(':').uppercase()) {
    "INSUFFICIENT_STORAGE", "STORAGE" -> stringResource(R.string.storage_failure)
    "AUDIO_VALIDATION", "AUDIO_FAILED" -> stringResource(R.string.audio_failure)
    else -> stringResource(R.string.generation_failed)
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.1f MiB".format(bytes / (1_024.0 * 1_024.0))
}
