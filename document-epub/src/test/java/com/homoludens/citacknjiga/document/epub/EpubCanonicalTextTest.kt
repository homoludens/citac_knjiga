package com.homoludens.citacknjiga.document.epub

import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubCanonicalTextTest {
    @Test
    public fun rendererIsDeterministicAndFormatsTypedBlocksWithLocators() {
        val chapter = EpubChapter(
            id = "chapter-0",
            ordinal = 0,
            title = "Прво поглавље",
            sourcePath = "OEBPS/chapter.xhtml",
            sourceLocator = "OEBPS/chapter.xhtml#/html[1]/body[1]",
            blocks = listOf(
                EpubNarrationBlock(0, NarrationBlockType.HEADING, "Наслов", "OEBPS/chapter.xhtml#/h1[1]", 2),
                EpubNarrationBlock(1, NarrationBlockType.PARAGRAPH, "Обичан текст.", "OEBPS/chapter.xhtml#/p[1]"),
                EpubNarrationBlock(2, NarrationBlockType.LIST_ITEM, "Ставка", "OEBPS/chapter.xhtml#/li[1]"),
                EpubNarrationBlock(3, NarrationBlockType.QUOTE, "Цитат", "OEBPS/chapter.xhtml#/blockquote[1]"),
                EpubNarrationBlock(4, NarrationBlockType.POETRY, "Први стих\nДруги стих", "OEBPS/chapter.xhtml#/div[1]"),
                EpubNarrationBlock(5, NarrationBlockType.NOTE, "Белешка", "OEBPS/chapter.xhtml#/aside[1]"),
                EpubNarrationBlock(6, NarrationBlockType.SCENE_BREAK, "", "OEBPS/chapter.xhtml#/hr[1]"),
            ),
        )

        val renderer = EpubMarkdownRenderer()
        val first = renderer.render(chapter)

        assertEquals(first, renderer.render(chapter))
        assertEquals(
            """<!-- chapter-source: OEBPS/chapter.xhtml#/html[1]/body[1] -->

# Прво поглавље

<!-- source: OEBPS/chapter.xhtml#/h1[1] -->
## Наслов

<!-- source: OEBPS/chapter.xhtml#/p[1] -->
Обичан текст.

<!-- source: OEBPS/chapter.xhtml#/li[1] -->
- Ставка

<!-- source: OEBPS/chapter.xhtml#/blockquote[1] -->
> Цитат

<!-- source: OEBPS/chapter.xhtml#/div[1] -->
Први стих
Други стих

<!-- source: OEBPS/chapter.xhtml#/aside[1] -->
[^note-6]: Белешка

<!-- source: OEBPS/chapter.xhtml#/hr[1] -->
* * *
""".trimIndent() + "\n",
            first,
        )
    }

    @Test
    public fun serviceReturnsAndPersistsActionableWarnings() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val document = EpubDocument(
            projectId = "warnings",
            sourceUri = "content://private",
            sourceFingerprint = "fingerprint",
            sourcePath = "${root.path}/sources/warnings/source.epub",
            metadata = EpubPublicationMetadata("Untitled EPUB", emptyList(), null, null, missingFields = setOf("title", "creator", "language")),
            cover = null,
            tableOfContents = emptyList(),
            chapters = listOf(
                chapter("empty", 0, EpubNarrationBlock(0, NarrationBlockType.SKIPPED, "", "empty.xhtml#/body[1]", skippedReason = "chapter contains no narratable blocks")),
                chapter("unsupported", 1, EpubNarrationBlock(0, NarrationBlockType.SKIPPED, "table text", "unsupported.xhtml#/table[1]", skippedReason = "unsupported block construct")),
                chapter("skipped", 2, EpubNarrationBlock(0, NarrationBlockType.SKIPPED, "", "skipped.xhtml#/audio[1]", skippedReason = "content is unavailable")),
            ),
            navigationIssues = listOf(EpubNavigationIssue("Navigation target is invalid", "OEBPS/toc.ncx#/navigation")),
        )

        val result = EpubCanonicalTextService(storage, AtomicArtifactStore(storage)).renderAndPersist(document)

        val published = result as EpubCanonicalTextResult.Published
        assertEquals(
            setOf(
                EpubImportWarningCode.MISSING_METADATA,
                EpubImportWarningCode.MALFORMED_NAVIGATION,
                EpubImportWarningCode.EMPTY_CHAPTER,
                EpubImportWarningCode.UNSUPPORTED_CONTENT,
                EpubImportWarningCode.SKIPPED_CONTENT,
                EpubImportWarningCode.CLEANUP_UNCERTAIN,
            ),
            published.warnings.map { it.code }.toSet(),
        )
        assertTrue(published.warnings.all { it.message.isNotBlank() && it.action.isNotBlank() })
        assertTrue(published.warnings.any { it.sourceLocator == "OEBPS/toc.ncx#/navigation" })
        assertTrue(published.warningsPath.readText().contains("CLEANUP_UNCERTAIN"))
        assertTrue(storage.canonicalChapterText("warnings", "unsupported").readText().contains("recovered-text: table text"))
    }

    @Test
    public fun publicationFailureDoesNotLeavePartialMarkdown() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val document = document("failure", chapter("chapter", 0, paragraph("Text", "chapter.xhtml#/p[1]")))
        storage.importWarnings("failure").mkdirs()

        val result = EpubCanonicalTextService(storage, AtomicArtifactStore(storage)).renderAndPersist(document)

        assertTrue(result is EpubCanonicalTextResult.Failed)
        assertFalse(storage.canonicalChapterText("failure", "chapter").exists())
    }

    @Test
    public fun canonicalAndWarningPathsRejectEscapingProjectIds() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())

        assertTrue(runCatching { storage.canonicalChapterText("../outside", "chapter") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { storage.importWarnings("book/child") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(storage.importWarnings("book").canonicalFile.toPath().startsWith(storage.rootDirectory.toPath()))
    }

    private fun document(projectId: String, vararg chapters: EpubChapter): EpubDocument = EpubDocument(
        projectId = projectId,
        sourceUri = "content://private",
        sourceFingerprint = "fingerprint",
        sourcePath = "source.epub",
        metadata = EpubPublicationMetadata("Title", listOf("Author"), "sr", "id"),
        cover = null,
        tableOfContents = emptyList(),
        chapters = chapters.toList(),
    )

    private fun chapter(id: String, ordinal: Int, block: EpubNarrationBlock): EpubChapter = EpubChapter(
        id = id,
        ordinal = ordinal,
        title = "Chapter ${ordinal + 1}",
        sourcePath = "chapter.xhtml",
        sourceLocator = "chapter.xhtml#/body[1]",
        blocks = listOf(block),
    )

    private fun paragraph(text: String, locator: String): EpubNarrationBlock =
        EpubNarrationBlock(0, NarrationBlockType.PARAGRAPH, text, locator)
}
