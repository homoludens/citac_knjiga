package com.homoludens.citacknjiga.library

import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class DocumentTextPreviewTest {
    @Test
    public fun largeDocumentsAreSampledUntilFullTextIsRequested() {
        val fullText = "Почетак " + "садржај ".repeat(2_000) + "КРАЈ"
        val preview = DocumentTextPreviewPolicy.from(
            chapters = listOf(chapter()),
            blocks = listOf(block(fullText)),
        )

        assertTrue(preview.isLargeDocument)
        assertTrue(preview.sampledText.length < preview.fullText.length)
        assertFalse(preview.sampledText.contains("КРАЈ"))
        assertEquals(preview.sampledText, preview.text(showFullText = false))
        assertEquals(preview.fullText, preview.text(showFullText = true))
    }

    @Test
    public fun previewUsesNarratableTextWithoutChangingImportedBlocks() {
        val source = block("Оригинални текст")
        val skipped = block("Не приказуј", ordinal = 1, type = NarrationBlockType.SKIPPED)

        val preview = DocumentTextPreviewPolicy.from(listOf(chapter()), listOf(source, skipped))

        assertEquals("Поглавље\n\nОригинални текст", preview.fullText)
        assertEquals("Оригинални текст", source.sourceText)
        assertEquals(NarrationBlockType.SKIPPED, skipped.blockType)
    }

    private fun chapter() = ChapterEntity(
        id = "chapter-1",
        bookProjectId = "book-1",
        ordinal = 0,
        title = "Поглавље",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun block(text: String, ordinal: Int = 0, type: NarrationBlockType = NarrationBlockType.PARAGRAPH) =
        NarrationBlockEntity(
            id = "block-$ordinal",
            chapterId = "chapter-1",
            ordinal = ordinal,
            blockType = type,
            sourceText = text,
            createdAt = 1,
            updatedAt = 1,
        )
}
