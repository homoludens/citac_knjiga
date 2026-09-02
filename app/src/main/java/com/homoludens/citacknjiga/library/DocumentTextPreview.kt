package com.homoludens.citacknjiga.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType

public data class DocumentTextPreview(
    public val fullText: String,
    public val sampledText: String,
    public val isLargeDocument: Boolean,
) {
    public fun text(showFullText: Boolean): String =
        if (showFullText && isLargeDocument) fullText else sampledText
}

/** Keeps the default preview bounded without changing the imported narration blocks. */
public object DocumentTextPreviewPolicy {
    public const val LARGE_DOCUMENT_CHARACTER_LIMIT: Int = 8_000
    public const val SAMPLE_CHARACTER_LIMIT: Int = 2_000

    public fun from(
        chapters: List<ChapterEntity>,
        blocks: Collection<NarrationBlockEntity>,
    ): DocumentTextPreview {
        val text = chapters.sortedBy { it.ordinal }.mapNotNull { chapter ->
            val chapterText = blocks
                .asSequence()
                .filter { it.chapterId == chapter.id }
                .filter { it.blockType != NarrationBlockType.SKIPPED }
                .sortedBy { it.ordinal }
                .map { it.sourceText.trim() }
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            chapterText.takeIf(String::isNotBlank)?.let { "${chapter.title}\n\n$it" }
        }.joinToString("\n\n")
        val isLarge = text.length > LARGE_DOCUMENT_CHARACTER_LIMIT
        val sampled = if (isLarge) {
            text.take(SAMPLE_CHARACTER_LIMIT).trimEnd() + "\n…"
        } else {
            text
        }
        return DocumentTextPreview(fullText = text, sampledText = sampled, isLargeDocument = isLarge)
    }
}

@Composable
public fun DocumentTextPreviewScreen(
    book: LibraryBookDisplay?,
    blocks: Collection<NarrationBlockEntity>,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (book == null) {
        Text(stringResource(R.string.book_not_found), modifier = modifier.padding(24.dp))
        return
    }
    if (loading) {
        Text(stringResource(R.string.document_preview_loading), modifier = modifier.padding(24.dp))
        return
    }

    val preview = remember(book.project.id, book.chapters, blocks) {
        DocumentTextPreviewPolicy.from(
            chapters = book.chapters.map { it.chapter },
            blocks = blocks,
        )
    }
    var showFullText by remember { mutableStateOf(false) }
    val title = book.title.ifBlank { stringResource(R.string.book_fallback) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.document_preview_description))
        if (preview.isLargeDocument && !showFullText) {
            Text(stringResource(R.string.document_preview_sample_label), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(stringResource(R.string.document_preview_full_label), style = MaterialTheme.typography.titleMedium)
        }
        if (preview.isLargeDocument) {
            if (showFullText) {
                OutlinedButton(
                    onClick = { showFullText = false },
                    modifier = Modifier.fillMaxWidth().testTag("document-preview-sample-${book.project.id}"),
                ) { Text(stringResource(R.string.document_preview_show_sample)) }
            } else {
                Button(
                    onClick = { showFullText = true },
                    modifier = Modifier.fillMaxWidth().testTag("document-preview-full-${book.project.id}"),
                ) { Text(stringResource(R.string.document_preview_show_full)) }
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("document-preview-text-${book.project.id}"),
        ) {
            Text(
                preview.text(showFullText),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
