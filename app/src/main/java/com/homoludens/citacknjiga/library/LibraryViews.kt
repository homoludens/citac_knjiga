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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.generation.GenerationScope
import com.homoludens.citacknjiga.ui.theme.CitacKnjigaTheme

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
    onRegenerate: (String, GenerationScope) -> Unit = { _, _ -> },
    regenerationFeedback: RegenerationFeedback? = null,
    showTitle: Boolean = true,
    compactCards: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineSmall)
        if (state.books.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.no_books),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.books.forEach { book ->
                LibraryBookCard(
                    book,
                    onClick = { onBookClick(book.project.id) },
                    onGenerationAction = onGenerationAction,
                    onDeleteBook = onDeleteBook,
                    onRegenerate = onRegenerate,
                    regenerationFeedback = regenerationFeedback,
                    compact = compactCards,
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
    onRegenerate: (String, GenerationScope) -> Unit,
    regenerationFeedback: RegenerationFeedback?,
    compact: Boolean,
) {
    val title = book.title.ifBlank { stringResource(R.string.book_fallback) }
    val author = book.author.ifBlank { stringResource(R.string.author_fallback) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BookCover(book.coverPath, title, Modifier.size(width = 72.dp, height = 100.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (compact) {
                        CompactBookActions(book, onRegenerate, onDeleteBook)
                    }
                }
                Text(author, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.chapters_ready_format, book.readyChapterCount, book.chapters.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.book_duration_format, formatDuration(book.chapters.sumOf { it.durationMs })),
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
                RegenerationFeedbackContent(book, regenerationFeedback, onRegenerate)
                if (!compact) {
                    RegenerateBookAction(book, onRegenerate)
                    DeleteBookAction(book, onDeleteBook)
                }
            }
        }
    }
}

@Composable
private fun CompactBookActions(
    book: LibraryBookDisplay,
    onRegenerate: (String, GenerationScope) -> Unit,
    onDeleteBook: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmRegeneration by remember { mutableStateOf(false) }
    var confirmDeletion by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.book_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.regenerate_book)) },
                onClick = { expanded = false; confirmRegeneration = true },
                modifier = Modifier.testTag("regenerate-book-${book.project.id}"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_book)) },
                onClick = { expanded = false; confirmDeletion = true },
                modifier = Modifier.testTag("delete-book-${book.project.id}"),
            )
        }
    }
    if (confirmRegeneration) {
        AlertDialog(
            onDismissRequest = { confirmRegeneration = false },
            title = { Text(stringResource(R.string.regenerate_book_title)) },
            text = { Text(stringResource(R.string.regenerate_book_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRegeneration = false
                        onRegenerate(book.project.id, GenerationScope.CompleteBook)
                    },
                    modifier = Modifier.testTag("confirm-regenerate-book-${book.project.id}"),
                ) { Text(stringResource(R.string.regenerate_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRegeneration = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDeletion) {
        AlertDialog(
            onDismissRequest = { confirmDeletion = false },
            title = { Text(stringResource(R.string.delete_book_title)) },
            text = { Text(stringResource(R.string.delete_book_message, book.title)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeletion = false
                        onDeleteBook(book.project.id)
                    },
                    modifier = Modifier.testTag("confirm-delete-book-${book.project.id}"),
                ) { Text(stringResource(R.string.delete_book_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
public fun BookDetailScreen(
    book: LibraryBookDisplay?,
    onOpenTextPreview: () -> Unit = {},
    onGenerationAction: (String, GenerationAction) -> Unit = { _, _ -> },
    onDeleteBook: (String) -> Unit = {},
    onRegenerate: (String, GenerationScope) -> Unit = { _, _ -> },
    regenerationFeedback: RegenerationFeedback? = null,
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
        OutlinedButton(
            onClick = onOpenTextPreview,
            modifier = Modifier.fillMaxWidth().testTag("document-preview-${book.project.id}"),
        ) { Text(stringResource(R.string.document_preview_action)) }
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
        RegenerationFeedbackContent(book, regenerationFeedback, onRegenerate)
        Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleLarge)
        book.chapters.forEach { chapter ->
            ChapterRow(chapter, book.project.id, onRegenerate)
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
        RegenerateBookAction(book, onRegenerate)
        DeleteBookAction(book, onDeleteBook)
    }
}

@Composable
private fun RegenerateBookAction(
    book: LibraryBookDisplay,
    onRegenerate: (String, GenerationScope) -> Unit,
) {
    var showConfirmation by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showConfirmation = true },
        modifier = Modifier.fillMaxWidth().testTag("regenerate-book-${book.project.id}"),
    ) { Text(stringResource(R.string.regenerate_book)) }
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.regenerate_book_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.regenerate_book_message,
                        book.title.ifBlank { stringResource(R.string.book_fallback) },
                    ),
                    modifier = Modifier.testTag("regenerate-book-warning-${book.project.id}"),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        onRegenerate(book.project.id, GenerationScope.CompleteBook)
                    },
                    modifier = Modifier.testTag("confirm-regenerate-book-${book.project.id}"),
                ) { Text(stringResource(R.string.regenerate_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmation = false },
                    modifier = Modifier.testTag("cancel-regenerate-book-${book.project.id}"),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RegenerationFeedbackContent(
    book: LibraryBookDisplay,
    feedback: RegenerationFeedback?,
    onRegenerate: (String, GenerationScope) -> Unit,
) {
    if (feedback?.projectId != book.project.id) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = if (feedback.status == RegenerationResultStatus.FAILED) {
                LiveRegionMode.Assertive
            } else {
                LiveRegionMode.Polite
            } },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                when (feedback.status) {
                    RegenerationResultStatus.QUEUING -> R.string.regeneration_queuing
                    RegenerationResultStatus.QUEUED -> R.string.regeneration_queued
                    RegenerationResultStatus.FAILED -> R.string.regeneration_failed
                },
            ),
            color = if (feedback.status == RegenerationResultStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (feedback.status == RegenerationResultStatus.FAILED) {
            OutlinedButton(
                onClick = { onRegenerate(book.project.id, feedback.scope) },
                modifier = Modifier.fillMaxWidth().testTag("retry-regeneration-${book.project.id}"),
            ) { Text(stringResource(R.string.regeneration_retry)) }
        }
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
    val status = book.generationStatus ?: book.status.generationRunStatus()
    val progress = book.generationProgress.text()
    val statusDescription = generationStateDescription(status)
    val progressAccessibility = stringResource(
        R.string.generation_progress_accessibility_format,
        progress,
        statusDescription,
    )
    val statusColor = when (status) {
        GenerationRunStatus.FAILED -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier.testTag("generation-progress-${book.project.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            progress,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .testTag("generation-progress-text-${book.project.id}")
                .semantics {
                    liveRegion = if (status == GenerationRunStatus.FAILED) {
                        LiveRegionMode.Assertive
                    } else {
                        LiveRegionMode.Polite
                    }
                    stateDescription = statusDescription
                },
        )
        if (status == GenerationRunStatus.RUNNING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("generation-progress-bar-${book.project.id}")
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(0f, 0f..1f)
                        contentDescription = progressAccessibility
                        stateDescription = progress
                    },
            )
        } else {
            LinearProgressIndicator(
                progress = { book.generationProgress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("generation-progress-bar-${book.project.id}")
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(book.generationProgress.fraction, 0f..1f)
                        contentDescription = progressAccessibility
                        stateDescription = progress
                    },
            )
        }
        Text(
            statusDescription,
            color = statusColor,
            modifier = Modifier
                .testTag("generation-status-${book.project.id}")
                .semantics {
                    liveRegion = if (status == GenerationRunStatus.FAILED || status == null) {
                        LiveRegionMode.Assertive
                    } else {
                        LiveRegionMode.Polite
                    }
                },
        )
        if (runId != null) {
            when (status) {
                GenerationRunStatus.RUNNING -> {
                    GenerationActionButton(stringResource(R.string.generation_pause)) {
                        onGenerationAction(runId, GenerationAction.PAUSE)
                    }
                    GenerationActionButton(stringResource(R.string.generation_cancel)) {
                        onGenerationAction(runId, GenerationAction.CANCEL)
                    }
                }
                GenerationRunStatus.PAUSED -> {
                    GenerationActionButton(stringResource(R.string.generation_resume)) {
                        onGenerationAction(runId, GenerationAction.RESUME)
                    }
                    GenerationActionButton(stringResource(R.string.generation_cancel)) {
                        onGenerationAction(runId, GenerationAction.CANCEL)
                    }
                }
                GenerationRunStatus.QUEUED -> {
                    GenerationActionButton(stringResource(R.string.generation_cancel)) {
                        onGenerationAction(runId, GenerationAction.CANCEL)
                    }
                }
                GenerationRunStatus.FAILED -> {
                    GenerationActionButton(stringResource(R.string.generation_retry)) {
                        onGenerationAction(runId, GenerationAction.RETRY)
                    }
                }
                GenerationRunStatus.COMPLETED -> Unit
                GenerationRunStatus.CANCELLED -> {
                    GenerationActionButton(stringResource(R.string.generation_retry)) {
                        onGenerationAction(runId, GenerationAction.RETRY)
                    }
                }
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
private fun ChapterRow(
    chapter: ChapterDisplay,
    projectId: String,
    onRegenerate: (String, GenerationScope) -> Unit,
) {
    var showConfirmation by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${chapter.chapter.ordinal + 1}. ${chapter.chapter.title}", modifier = Modifier.weight(1f))
            Text(chapter.chapter.status.displayName(), style = MaterialTheme.typography.labelMedium)
        }
        if (chapter.progress.total > 0) {
            val status = chapter.generationStatus ?: chapter.chapter.status.generationRunStatus()
            val statusDescription = generationStateDescription(status)
            val progress = chapter.progress.chapterText(chapter.durationMs)
            val progressAccessibility = stringResource(
                R.string.generation_progress_accessibility_format,
                progress,
                statusDescription,
            )
            LinearProgressIndicator(
                progress = { chapter.progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chapter-progress-bar-${chapter.chapter.id}")
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(chapter.progress.fraction, 0f..1f)
                        contentDescription = progressAccessibility
                        stateDescription = progress
                    },
            )
            Text(
                progress,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    liveRegion = if (status == GenerationRunStatus.FAILED) {
                        LiveRegionMode.Assertive
                    } else {
                        LiveRegionMode.Polite
                    }
                    stateDescription = statusDescription
                },
            )
            Text(
                statusDescription,
                color = if (status == GenerationRunStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
        OutlinedButton(
            onClick = { showConfirmation = true },
            modifier = Modifier.fillMaxWidth().testTag("regenerate-chapter-${chapter.chapter.id}"),
        ) { Text(stringResource(R.string.regenerate_chapter)) }
        if (showConfirmation) {
            AlertDialog(
                onDismissRequest = { showConfirmation = false },
                title = { Text(stringResource(R.string.regenerate_chapter_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.regenerate_chapter_message,
                            chapter.chapter.title,
                        ),
                        modifier = Modifier.testTag("regenerate-chapter-warning-${chapter.chapter.id}"),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmation = false
                            onRegenerate(projectId, GenerationScope.Chapter(chapter.chapter.id))
                        },
                        modifier = Modifier.testTag("confirm-regenerate-chapter-${chapter.chapter.id}"),
                    ) { Text(stringResource(R.string.regenerate_confirm)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showConfirmation = false },
                        modifier = Modifier.testTag("cancel-regenerate-chapter-${chapter.chapter.id}"),
                    ) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
public fun BookCover(path: String?, title: String, modifier: Modifier = Modifier) {
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

private fun BookProjectStatus.generationRunStatus(): GenerationRunStatus? = when (this) {
    BookProjectStatus.GENERATING -> GenerationRunStatus.RUNNING
    BookProjectStatus.PAUSED -> GenerationRunStatus.PAUSED
    BookProjectStatus.COMPLETED -> GenerationRunStatus.COMPLETED
    BookProjectStatus.FAILED -> GenerationRunStatus.FAILED
    BookProjectStatus.IMPORTING,
    BookProjectStatus.READY,
    -> null
}

@Composable
private fun ChapterStatus.displayName(): String = when (this) {
    ChapterStatus.PENDING -> stringResource(R.string.status_pending)
    ChapterStatus.GENERATING -> stringResource(R.string.status_generating)
    ChapterStatus.PARTIAL -> stringResource(R.string.status_partial)
    ChapterStatus.READY -> stringResource(R.string.status_ready)
    ChapterStatus.FAILED -> stringResource(R.string.status_failed)
}

private fun ChapterStatus.generationRunStatus(): GenerationRunStatus? = when (this) {
    ChapterStatus.GENERATING -> GenerationRunStatus.RUNNING
    ChapterStatus.READY -> GenerationRunStatus.COMPLETED
    ChapterStatus.FAILED -> GenerationRunStatus.FAILED
    ChapterStatus.PENDING,
    ChapterStatus.PARTIAL,
    -> null
}

@Composable
private fun generationStateDescription(status: GenerationRunStatus?): String = when (status) {
    GenerationRunStatus.RUNNING -> stringResource(R.string.generation_running)
    GenerationRunStatus.PAUSED -> stringResource(R.string.generation_paused)
    GenerationRunStatus.QUEUED -> stringResource(R.string.generation_queued)
    GenerationRunStatus.COMPLETED -> stringResource(R.string.generation_completed)
    GenerationRunStatus.FAILED -> stringResource(R.string.generation_failed_action)
    GenerationRunStatus.CANCELLED -> stringResource(R.string.generation_cancelled)
    null -> stringResource(R.string.generation_unavailable)
}

@Composable
private fun ProgressDisplay.text(): String = if (usesWordEstimate) {
    stringResource(
        R.string.generation_progress_words_format,
        completedWords,
        totalWords,
        percentage,
    )
} else {
    stringResource(
        R.string.generation_progress_format,
        completed,
        total,
        percentage,
    )
}

@Composable
private fun ProgressDisplay.chapterText(durationMs: Long): String = if (usesWordEstimate) {
    stringResource(
        R.string.audio_progress_words_format,
        completedWords,
        totalWords,
        percentage,
        formatDuration(durationMs),
    )
} else {
    stringResource(
        R.string.audio_progress_format,
        completed,
        total,
        percentage,
        formatDuration(durationMs),
    )
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

@Preview(showBackground = true)
@Composable
private fun LibraryPreview() {
    CitacKnjigaTheme {
        LibraryScreen(state = LibraryViewState(), onBookClick = {})
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.1f MiB".format(bytes / (1_024.0 * 1_024.0))
}
