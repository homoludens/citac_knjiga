package com.homoludens.citacknjiga.core.document

import com.homoludens.citacknjiga.core.database.NarrationBlockType

public typealias SourceLocator = String

/** Stable source identity used by every imported document format. */
public data class PageLocator(
    public val fingerprint: String,
    public val pageNumber: Int,
) {
    init {
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Fingerprint must be lowercase SHA-256" }
        require(pageNumber >= 1) { "Page numbers are 1-based" }
    }

    public fun block(ordinal: Int): String {
        require(ordinal >= 0) { "Block ordinals are zero-based" }
        return "$this/block/$ordinal"
    }

    override fun toString(): String = "pdf:sha256=$fingerprint/page/$pageNumber"
}

public data class ImportProvenance(
    public val fingerprint: String,
    public val sourceUri: String?,
    public val sourcePath: String,
    public val projectId: String = "",
) {
    init {
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Fingerprint must be lowercase SHA-256" }
    }
}

public data class DocumentBlock(
    public val ordinal: Int,
    public val type: NarrationBlockType,
    public val sourceText: String,
    public val locator: SourceLocator,
)

public data class DocumentChapter(
    public val ordinal: Int,
    public val title: String,
    public val locator: PageLocator,
    public val blocks: List<DocumentBlock>,
)

public data class DocumentIr(
    public val title: String,
    public val author: String?,
    public val language: String,
    public val chapters: List<DocumentChapter>,
    public val provenance: ImportProvenance,
) {
    /** Canonical, ordered representation for hashing and deterministic artifact tests. */
    public fun canonicalSerialization(): String = buildString {
        append("title=").append(escape(title)).append('\n')
        append("author=").append(escape(author.orEmpty())).append('\n')
        append("language=").append(escape(language)).append('\n')
        append("fingerprint=").append(provenance.fingerprint).append('\n')
        chapters.sortedBy { it.ordinal }.forEach { chapter ->
            append("chapter=").append(chapter.ordinal).append('|')
                .append(escape(chapter.title)).append('|').append(chapter.locator).append('\n')
            chapter.blocks.sortedBy { it.ordinal }.forEach { block ->
                append("block=").append(block.ordinal).append('|').append(block.type.name).append('|')
                    .append(escape(block.sourceText)).append('|').append(block.locator).append('\n')
            }
        }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '|' -> append("\\|")
                else -> append(character)
            }
        }
    }
}

public enum class ImportWarningCode {
    MULTI_COLUMN,
    EXTERNAL_RESOURCE,
}

public data class ImportWarning(
    public val code: ImportWarningCode,
    public val locator: SourceLocator,
    public val message: String,
    public val action: String,
)

public enum class ImportDiagnosticCode {
    PDF_FEATURE_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    SOURCE_CHANGED,
    SOURCE_TOO_LARGE,
    PAGE_COUNT_TOO_LARGE,
    PAGE_RANGE_INVALID,
    SELECTED_PAGES_TOO_MANY,
    PAGE_TEXT_TOO_LARGE,
    RANGE_TEXT_TOO_LARGE,
    INSPECTION_TIMEOUT,
    CANCELED,
    PROTECTED_PDF,
    MALFORMED_PDF,
    UNSUPPORTED_PDF,
    OCR_UNSUPPORTED,
    EMPTY_PAGE,
    UNRELIABLE_LAYOUT,
    EXTERNAL_RESOURCE_IGNORED,
    ACCEPTANCE_FAILED,
}

public data class ImportDiagnostic(
    public val code: ImportDiagnosticCode,
    public val locator: SourceLocator? = null,
    public val message: String,
    public val action: String,
    public val blocking: Boolean = true,
)
