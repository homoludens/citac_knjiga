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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus

@Composable
public fun LibraryScreen(
    state: LibraryViewState,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Библиотека", style = MaterialTheme.typography.headlineSmall)
        if (state.books.isEmpty()) {
            Text("Још нема прихваћених књига. Увезите EPUB испод.")
        } else {
            state.books.forEach { book ->
                LibraryBookCard(book, onClick = { onBookClick(book.project.id) })
            }
        }
    }
}

@Composable
private fun LibraryBookCard(book: LibraryBookDisplay, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BookCover(book.coverPath, Modifier.size(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                Text(book.author, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Поглавља: ${book.readyChapterCount}/${book.chapters.size} спремно",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (book.hasGenerationWork) {
                    GenerationProgress(book)
                }
                Text("Заузеће: ${formatBytes(book.storageBytes)}", style = MaterialTheme.typography.labelSmall)
                book.listeningProgress?.let { listening ->
                    Text("Слушање: ${listening.chapterTitle ?: "књига"}, ${formatDuration(listening.positionMs)}")
                }
                if (book.failures.isNotEmpty()) {
                    Text("Грешке: ${book.failures.size}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
public fun BookDetailScreen(
    book: LibraryBookDisplay?,
    modifier: Modifier = Modifier,
) {
    if (book == null) {
        Text("Књига није пронађена.", modifier = modifier.padding(24.dp))
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            BookCover(book.coverPath, Modifier.size(104.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.headlineSmall)
                Text(book.author)
                Text("Стање: ${book.status.displayName()}")
                Text("Заузеће: ${formatBytes(book.storageBytes)}")
            }
        }
        if (book.hasGenerationWork) GenerationProgress(book)
        book.listeningProgress?.let { listening ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Напредак слушања", style = MaterialTheme.typography.titleMedium)
                    Text("${listening.chapterTitle ?: "Поглавље"}: ${formatDuration(listening.positionMs)}")
                    listening.fraction?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Text("Поглавља", style = MaterialTheme.typography.titleLarge)
        book.chapters.forEach { chapter ->
            ChapterRow(chapter)
        }
        if (book.failures.isNotEmpty()) {
            Text("Грешке генерације", style = MaterialTheme.typography.titleLarge)
            book.failures.forEach { failure ->
                Text(failure, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun GenerationProgress(book: LibraryBookDisplay) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Генерисање: ${book.generationProgress.completed}/${book.generationProgress.total} делова",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { book.generationProgress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChapterRow(chapter: ChapterDisplay) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${chapter.chapter.ordinal + 1}. ${chapter.chapter.title}")
            Text(chapter.chapter.status.displayName(), style = MaterialTheme.typography.labelMedium)
        }
        if (chapter.progress.total > 0) {
            LinearProgressIndicator(progress = { chapter.progress.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "Звук: ${chapter.progress.completed}/${chapter.progress.total} делова, " +
                    "${formatDuration(chapter.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Заузеће: ${formatBytes(chapter.storageBytes)}", style = MaterialTheme.typography.labelSmall)
        chapter.chapter.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun BookCover(path: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "Насловна страна",
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
            Text("Књига", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun BookProjectStatus.displayName(): String = when (this) {
    BookProjectStatus.IMPORTING -> "увоз"
    BookProjectStatus.READY -> "спремна"
    BookProjectStatus.GENERATING -> "генерисање"
    BookProjectStatus.PAUSED -> "паузирано"
    BookProjectStatus.COMPLETED -> "завршена"
    BookProjectStatus.FAILED -> "грешка"
}

private fun ChapterStatus.displayName(): String = when (this) {
    ChapterStatus.PENDING -> "на чекању"
    ChapterStatus.GENERATING -> "генерисање"
    ChapterStatus.PARTIAL -> "делимично"
    ChapterStatus.READY -> "спремно"
    ChapterStatus.FAILED -> "грешка"
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
