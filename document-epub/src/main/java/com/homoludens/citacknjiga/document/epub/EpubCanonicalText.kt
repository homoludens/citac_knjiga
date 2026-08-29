package com.homoludens.citacknjiga.document.epub

import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File

public enum class EpubImportWarningCode {
    MISSING_METADATA,
    MALFORMED_NAVIGATION,
    EMPTY_CHAPTER,
    SKIPPED_CONTENT,
    UNSUPPORTED_CONTENT,
    CLEANUP_UNCERTAIN,
}

/** A user-facing warning with enough context to make the next action clear. */
public data class EpubImportWarning(
    public val code: EpubImportWarningCode,
    public val message: String,
    public val action: String,
    public val chapterId: String? = null,
    public val sourceLocator: String? = null,
)

public data class CanonicalChapterArtifact(
    public val chapterId: String,
    public val path: File,
    public val markdown: String,
    public val sizeBytes: Long,
    public val sha256: String,
)

public data class CanonicalChapterPreview(
    public val chapterId: String,
    public val title: String,
    public val narrationText: String,
    public val markdown: String,
    public val sizeBytes: Long,
)

public data class EpubCanonicalTextPreview(
    public val chapters: List<CanonicalChapterPreview>,
    public val warnings: List<EpubImportWarning>,
    public val warningReportSizeBytes: Long,
)

public sealed interface EpubCanonicalTextResult {
    public data class Published(
        public val projectId: String,
        public val chapters: List<CanonicalChapterArtifact>,
        public val warnings: List<EpubImportWarning>,
        public val warningsPath: File,
    ) : EpubCanonicalTextResult

    public data class Failed(
        public val warnings: List<EpubImportWarning>,
        public val cleanupUncertain: Boolean,
    ) : EpubCanonicalTextResult
}

/** Renders the typed IR without consulting database state or time. */
public class EpubMarkdownRenderer {
    public fun render(chapter: EpubChapter): String = buildString {
        append("<!-- chapter-source: ")
        append(commentSafe(chapter.sourceLocator))
        append(" -->\n\n# ")
        append(markdownLine(chapter.title))
        append('\n')
        chapter.blocks.forEach { block ->
            append("\n<!-- source: ")
            append(commentSafe(block.sourceLocator))
            append(" -->\n")
            append(renderBlock(block))
            append('\n')
        }
    }.toString()

    private fun renderBlock(block: EpubNarrationBlock): String = when (block.type) {
        NarrationBlockType.HEADING -> "${"#".repeat((block.headingLevel ?: 2).coerceIn(1, 6))} ${markdownLine(block.sourceText)}"
        NarrationBlockType.PARAGRAPH -> markdownParagraph(block.sourceText)
        NarrationBlockType.LIST_ITEM -> "- ${markdownLine(block.sourceText)}"
        NarrationBlockType.QUOTE -> quote(block.sourceText)
        NarrationBlockType.POETRY -> block.sourceText.replace("\r\n", "\n").replace('\r', '\n').trim()
        NarrationBlockType.CAPTION -> "*${markdownLine(block.sourceText)}*"
        NarrationBlockType.NOTE -> "[^note-${block.ordinal + 1}]: ${markdownLine(block.sourceText)}"
        NarrationBlockType.SCENE_BREAK -> "* * *"
        NarrationBlockType.SKIPPED -> "<!-- skipped: ${commentSafe(block.skippedReason ?: "unspecified")}" +
            if (block.sourceText.isBlank()) " -->" else "; recovered-text: ${commentSafe(block.sourceText)} -->"
    }

    private fun markdownParagraph(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').lineSequence()
            .map(::markdownLine)
            .joinToString(" ")
            .trim()

    private fun quote(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').lineSequence()
            .map { "> ${markdownLine(it)}" }
            .joinToString("\n")

    private fun markdownLine(value: String): String = value.trim()

    private fun commentSafe(value: String): String =
        value.replace("--", "- -").replace(">", "&gt;").replace("\r", " ").replace("\n", " ")
}

/** Publishes all chapter text and its warning report, rolling back on any publication failure. */
public class EpubCanonicalTextService(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val renderer: EpubMarkdownRenderer = EpubMarkdownRenderer(),
) {
    /** Renders the exact canonical output in memory; no private files are changed. */
    public fun preview(document: EpubDocument): EpubCanonicalTextPreview {
        val warnings = warningsFor(document)
        val chapters = document.chapters.map { chapter ->
            val markdown = renderer.render(chapter)
            CanonicalChapterPreview(
                chapterId = chapter.id,
                title = chapter.title,
                narrationText = chapter.blocks
                    .filter { it.type != NarrationBlockType.SKIPPED && it.sourceText.isNotBlank() }
                    .joinToString("\n\n") { it.sourceText },
                markdown = markdown,
                sizeBytes = markdown.toByteArray(Charsets.UTF_8).size.toLong(),
            )
        }
        return EpubCanonicalTextPreview(
            chapters = chapters,
            warnings = warnings,
            warningReportSizeBytes = warningReport(document.projectId, warnings)
                .toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    public fun renderAndPersist(document: EpubDocument): EpubCanonicalTextResult {
        val preview = try {
            preview(document)
        } catch (_: Exception) {
            val warnings = warningsFor(document)
            return EpubCanonicalTextResult.Failed(warnings, cleanupUncertain = false)
        }
        val targets = preview.chapters.map { chapter ->
            storage.canonicalChapterText(document.projectId, chapter.chapterId)
        }
        val warningTarget = storage.importWarnings(document.projectId)
        val previous = try {
            (targets + warningTarget).associateWith { it.takeIf(File::isFile)?.readBytes() }
        } catch (_: Exception) {
            return EpubCanonicalTextResult.Failed(preview.warnings, cleanupUncertain = false)
        }

        return try {
            val artifacts = preview.chapters.mapIndexed { index, chapter ->
                val published = artifactStore.publish(
                    ownerId = "canonical-${document.projectId}",
                    destination = targets[index],
                    writer = { output -> output.write(chapter.markdown.toByteArray(Charsets.UTF_8)) },
                    validator = { file -> require(file.readText(Charsets.UTF_8) == chapter.markdown) },
                )
                CanonicalChapterArtifact(
                    chapterId = chapter.chapterId,
                    path = published.file,
                    markdown = chapter.markdown,
                    sizeBytes = published.sizeBytes,
                    sha256 = published.sha256,
                )
            }
            artifactStore.publish(
                ownerId = "canonical-${document.projectId}",
                destination = warningTarget,
                writer = { output -> output.write(warningReport(document.projectId, preview.warnings).toByteArray(Charsets.UTF_8)) },
                validator = { file -> require(file.length() > 0) },
            )
            EpubCanonicalTextResult.Published(document.projectId, artifacts, preview.warnings, warningTarget)
        } catch (_: Exception) {
            val cleanupUncertain = !restore(previous)
            EpubCanonicalTextResult.Failed(
                warnings = if (cleanupUncertain) preview.warnings + cleanupWarning() else preview.warnings,
                cleanupUncertain = cleanupUncertain,
            )
        }
    }

    private fun warningsFor(document: EpubDocument): List<EpubImportWarning> = buildList {
        if (document.metadata.missingFields.isNotEmpty()) {
            val fields = document.metadata.missingFields.sorted().joinToString(", ")
            add(
                EpubImportWarning(
                    EpubImportWarningCode.MISSING_METADATA,
                    "Publication metadata is missing: $fields.",
                    "Add or confirm these fields before exporting the audiobook.",
                ),
            )
        }
        document.navigationIssues.forEach { issue ->
            add(
                EpubImportWarning(
                    EpubImportWarningCode.MALFORMED_NAVIGATION,
                    issue.message,
                    "Review the source location; chapter order was recovered from the spine.",
                    sourceLocator = issue.sourceLocator,
                ),
            )
        }
        document.chapters.forEach { chapter ->
            chapter.blocks.forEach { block ->
                if (block.type != NarrationBlockType.SKIPPED) return@forEach
                val reason = block.skippedReason ?: "content was skipped"
                when {
                    reason.contains("no narratable", ignoreCase = true) -> add(
                        EpubImportWarning(
                            EpubImportWarningCode.EMPTY_CHAPTER,
                            "Chapter ${chapter.ordinal + 1} contains no narratable content.",
                            "Review the source chapter or remove it before generation.",
                            chapter.id,
                            block.sourceLocator,
                        ),
                    )
                    reason.contains("unsupported", ignoreCase = true) -> add(
                        EpubImportWarning(
                            EpubImportWarningCode.UNSUPPORTED_CONTENT,
                            "Unsupported content was skipped in chapter ${chapter.ordinal + 1}.",
                            "Review the source location; supported narrative blocks remain available.",
                            chapter.id,
                            block.sourceLocator,
                        ),
                    )
                    else -> add(
                        EpubImportWarning(
                            EpubImportWarningCode.SKIPPED_CONTENT,
                            "Content was skipped in chapter ${chapter.ordinal + 1}: $reason.",
                            "Review the source location before generating audio.",
                            chapter.id,
                            block.sourceLocator,
                        ),
                    )
                }
                if (block.sourceText.isNotBlank()) add(
                    EpubImportWarning(
                        EpubImportWarningCode.CLEANUP_UNCERTAIN,
                        "Skipped content contains recovered text in chapter ${chapter.ordinal + 1}.",
                        "Review the source location to decide whether it should be narrated.",
                        chapter.id,
                        block.sourceLocator,
                    ),
                )
            }
        }
    }

    private fun cleanupWarning() = EpubImportWarning(
        EpubImportWarningCode.CLEANUP_UNCERTAIN,
        "Canonical text publication could not be fully rolled back.",
        "Check private storage before retrying the import.",
    )

    private fun restore(previous: Map<File, ByteArray?>): Boolean = previous.all { (file, bytes) ->
        runCatching {
            if (bytes == null) !file.exists() || file.delete() else artifactStore.publish(
                ownerId = "canonical-rollback",
                destination = file,
                writer = { output -> output.write(bytes) },
            )
        }.isSuccess
    }

    private fun warningReport(projectId: String, warnings: List<EpubImportWarning>): String = buildString {
        append("{\n  \"schema\": 1,\n  \"project_id\": \"")
        append(jsonSafe(projectId))
        append("\",\n  \"warnings\": [")
        warnings.forEachIndexed { index, warning ->
            if (index > 0) append(',')
            append("\n    {\"code\":\"")
            append(warning.code.name)
            append("\",\"message\":\"")
            append(jsonSafe(warning.message))
            append("\",\"action\":\"")
            append(jsonSafe(warning.action))
            warning.chapterId?.let { append("\",\"chapter_id\":\"").append(jsonSafe(it)) }
            warning.sourceLocator?.let { append("\",\"source_locator\":\"").append(jsonSafe(it)) }
            append("\"}")
        }
        if (warnings.isNotEmpty()) append('\n')
        append("  ]\n}\n")
    }

    private fun jsonSafe(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }
}
