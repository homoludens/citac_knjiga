package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType

/** Engine selection is data at the shared generation boundary, not a flow per engine. */
public enum class GenerationEngine(public val id: String) {
    KOKORO("kokoro"),
    VITS("vits"),
}

public sealed interface GenerationScope {
    public data object CompleteBook : GenerationScope

    public data class Chapter(public val chapterId: String) : GenerationScope {
        init {
            require(chapterId.isNotBlank()) { "Chapter id cannot be blank" }
        }
    }
}

/** The engine-facing part of an already imported narration block. */
public data class GenerationNarrationBlock(
    public val id: String,
    public val chapterId: String,
    public val ordinal: Int,
    public val text: String,
) {
    init {
        require(id.isNotBlank()) { "Narration block id cannot be blank" }
        require(chapterId.isNotBlank()) { "Narration block chapter id cannot be blank" }
        require(ordinal >= 0) { "Narration block ordinal cannot be negative" }
        require(text.isNotBlank()) { "Narration block text cannot be blank" }
    }
}

/** One request shape shared by PDF and EPUB regeneration and both TTS engines. */
public data class GenerationRequest(
    public val projectId: String,
    public val sourceFingerprint: String,
    public val scope: GenerationScope,
    public val engine: GenerationEngine,
    public val narrationBlocks: List<GenerationNarrationBlock>,
) {
    init {
        require(projectId.isNotBlank()) { "Project id cannot be blank" }
        require(sourceFingerprint.isNotBlank()) { "Source fingerprint cannot be blank" }
        require(narrationBlocks.map { it.id }.toSet().size == narrationBlocks.size) {
            "Generation request contains duplicate narration blocks"
        }
    }
}

/** Builds a request from persisted import output without knowing the source document format. */
public object GenerationRequestFactory {
    public fun fromExistingNarrationBlocks(
        project: BookProjectEntity,
        chapters: Collection<ChapterEntity>,
        narrationBlocks: Collection<NarrationBlockEntity>,
        scope: GenerationScope,
        engine: GenerationEngine,
    ): GenerationRequest {
        val projectChapters = chapters
            .filter { it.bookProjectId == project.id }
            .also { rows ->
                require(rows.map { it.id }.toSet().size == rows.size) {
                    "Project chapters contain duplicate ids"
                }
            }
        val selectedChapters = when (scope) {
            GenerationScope.CompleteBook -> projectChapters
            is GenerationScope.Chapter -> projectChapters.filter { it.id == scope.chapterId }.also { rows ->
                require(rows.size == 1) { "Chapter ${scope.chapterId} does not belong to project ${project.id}" }
            }
        }
        val selectedChapterIds = selectedChapters.map { it.id }.toSet()
        val blocks = narrationBlocks
            .asSequence()
            .filter { it.chapterId in selectedChapterIds }
            .filter { it.blockType != NarrationBlockType.SKIPPED && it.sourceText.isNotBlank() }
            .sortedWith(compareBy<NarrationBlockEntity> { chapterOrdinal(selectedChapters, it.chapterId) }
                .thenBy { it.ordinal }
                .thenBy { it.id })
            .map { block ->
                GenerationNarrationBlock(
                    id = block.id,
                    chapterId = block.chapterId,
                    ordinal = block.ordinal,
                    text = block.sourceText,
                )
            }
            .toList()

        return GenerationRequest(
            projectId = project.id,
            sourceFingerprint = project.sourceFingerprint,
            scope = scope,
            engine = engine,
            narrationBlocks = blocks,
        )
    }

    private fun chapterOrdinal(chapters: List<ChapterEntity>, chapterId: String): Int =
        chapters.first { it.id == chapterId }.ordinal
}
