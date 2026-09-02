package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.DocumentChapter
import com.homoludens.citacknjiga.core.document.DocumentIr
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.PageLocator

public object PdfDocumentProjector {
    public fun toIr(
        preview: PdfImportPreview,
        title: String? = null,
        author: String? = null,
        language: String = "sr",
    ): DocumentIr {
        require(preview.canAccept) { "A blocking PDF diagnostic prevents projection" }
        val chapters = preview.inspection.pages.mapIndexedNotNull { index, page ->
            val blocks = page.blocks.filter { it.block.sourceText.isNotBlank() }.mapIndexed { ordinal, block ->
                DocumentBlock(
                    ordinal = ordinal,
                    type = block.block.type,
                    sourceText = block.block.sourceText,
                    locator = page.locator.block(ordinal),
                )
            }
            if (blocks.isEmpty()) null else DocumentChapter(
                ordinal = index,
                title = title?.takeIf(String::isNotBlank) ?: "Page ${page.pageNumber}",
                locator = page.locator,
                blocks = blocks,
            )
        }
        return DocumentIr(
            title = title?.takeIf(String::isNotBlank) ?: "Imported PDF",
            author = author,
            language = language,
            chapters = chapters,
            provenance = preview.inspection.provenance,
        )
    }

    public fun toRoomProjection(
        source: ImportedPdfSource,
        document: DocumentIr,
        now: Long,
        canonicalChapterPaths: Map<String, String> = emptyMap(),
    ): PdfRoomProjection {
        require(source.projectId.isNotBlank())
        require(source.fingerprint == document.provenance.fingerprint)
        val project = BookProjectEntity(
            id = source.projectId,
            title = document.title,
            author = document.author,
            sourceUri = source.sourceUri,
            sourceFingerprint = source.fingerprint,
            sourcePath = source.sourceFile.path,
            language = document.language,
            status = BookProjectStatus.READY,
            createdAt = now,
            updatedAt = now,
        )
        val chapters = document.chapters.sortedBy { it.ordinal }.map { chapter ->
            ChapterEntity(
                id = chapterId(source.projectId, chapter),
                bookProjectId = source.projectId,
                ordinal = chapter.ordinal,
                title = chapter.title,
                sourceLocator = chapter.locator.toString(),
                canonicalMarkdownPath = canonicalChapterPaths[chapterId(source.projectId, chapter)],
                status = ChapterStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        }
        val blocks = document.chapters.flatMap { chapter ->
            val chapterId = chapterId(source.projectId, chapter)
            chapter.blocks.sortedBy { it.ordinal }.map { block ->
                NarrationBlockEntity(
                    id = "$chapterId-block-${block.ordinal}",
                    chapterId = chapterId,
                    ordinal = block.ordinal,
                    blockType = block.type,
                    sourceText = block.sourceText,
                    sourceLocator = block.locator,
                    status = NarrationBlockStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        return PdfRoomProjection(project, chapters, blocks)
    }

    public fun chapterId(projectId: String, chapter: DocumentChapter): String =
        "$projectId-pdf-page-${chapter.locator.pageNumber}"
}

public data class PdfRoomProjection(
    public val project: BookProjectEntity,
    public val chapters: List<ChapterEntity>,
    public val narrationBlocks: List<NarrationBlockEntity>,
)

public class PdfMarkdownRenderer {
    public fun render(chapter: DocumentChapter): String = buildString {
        append("<!-- chapter-source: ").append(chapter.locator).append(" -->\n\n# ")
            .append(chapter.title.trim()).append('\n')
        chapter.blocks.sortedBy { it.ordinal }.forEach { block ->
            append("\n<!-- source: ").append(block.locator).append(" -->\n")
            append(block.sourceText.trim()).append('\n')
        }
    }
}
