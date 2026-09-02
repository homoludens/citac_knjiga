package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class GenerationRequestTest {
    @Test
    public fun pdfAndEpubUseTheSameRequestShape() {
        val pdf = request(project(fingerprint = "pdf-fingerprint"), "pdf-locator")
        val epub = request(project(fingerprint = "epub-fingerprint"), "epub-locator")

        assertEquals(pdf.copy(sourceFingerprint = epub.sourceFingerprint), epub)
        assertEquals(GenerationEngine.KOKORO, pdf.engine)
        assertEquals(GenerationScope.CompleteBook, pdf.scope)
    }

    @Test
    public fun completeBookSelectsAllProjectBlocksInChapterOrder() {
        val project = project()
        val chapters = listOf(chapter("chapter-2", project.id, 2), chapter("chapter-1", project.id, 1))
        val blocks = listOf(
            block("other", "other-chapter", 0, "outside"),
            block("late", "chapter-2", 0, "Late"),
            block("skipped", "chapter-1", 0, "ignored", NarrationBlockType.SKIPPED),
            block("early", "chapter-1", 1, "Early"),
            block("blank", "chapter-1", 2, " "),
        )

        val request = GenerationRequestFactory.fromExistingNarrationBlocks(
            project = project,
            chapters = chapters,
            narrationBlocks = blocks,
            scope = GenerationScope.CompleteBook,
            engine = GenerationEngine.VITS,
        )

        assertEquals(listOf("early", "late"), request.narrationBlocks.map { it.id })
        assertEquals(listOf("chapter-1", "chapter-2"), request.narrationBlocks.map { it.chapterId })
    }

    @Test
    public fun chapterScopeSelectsOnlyTheRequestedChapter() {
        val project = project()
        val chapters = listOf(chapter("chapter-1", project.id, 1), chapter("chapter-2", project.id, 2))
        val request = GenerationRequestFactory.fromExistingNarrationBlocks(
            project = project,
            chapters = chapters,
            narrationBlocks = listOf(
                block("one", "chapter-1", 0, "One"),
                block("two", "chapter-2", 0, "Two"),
            ),
            scope = GenerationScope.Chapter("chapter-2"),
            engine = GenerationEngine.VITS,
        )

        assertEquals(listOf("two"), request.narrationBlocks.map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRequestFactory.fromExistingNarrationBlocks(
                project,
                chapters,
                emptyList(),
                GenerationScope.Chapter("other-project-chapter"),
                GenerationEngine.VITS,
            )
        }
    }

    private fun request(project: BookProjectEntity, locator: String) =
        GenerationRequestFactory.fromExistingNarrationBlocks(
            project = project,
            chapters = listOf(chapter("chapter", project.id, 0)),
            narrationBlocks = listOf(block("block", "chapter", 0, "Text", sourceLocator = locator)),
            scope = GenerationScope.CompleteBook,
            engine = GenerationEngine.KOKORO,
        )

    private fun project(fingerprint: String = "fingerprint") = BookProjectEntity(
        id = "book",
        title = "Book",
        sourceUri = "content://source",
        sourceFingerprint = fingerprint,
        status = BookProjectStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(id: String, projectId: String, ordinal: Int) = ChapterEntity(
        id = id,
        bookProjectId = projectId,
        ordinal = ordinal,
        title = id,
        status = ChapterStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun block(
        id: String,
        chapterId: String,
        ordinal: Int,
        text: String,
        type: NarrationBlockType = NarrationBlockType.PARAGRAPH,
        sourceLocator: String? = null,
    ) = NarrationBlockEntity(
        id = id,
        chapterId = chapterId,
        ordinal = ordinal,
        blockType = type,
        sourceText = text,
        sourceLocator = sourceLocator,
        status = NarrationBlockStatus.PROCESSED,
        createdAt = 1,
        updatedAt = 1,
    )
}
