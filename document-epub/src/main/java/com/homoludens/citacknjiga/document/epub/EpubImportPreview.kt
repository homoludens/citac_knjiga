package com.homoludens.citacknjiga.document.epub

import android.net.Uri

public data class EpubStorageEstimate(
    public val sourceBytes: Long,
    public val canonicalTextBytes: Long,
    public val coverBytes: Long,
    public val diagnosticsBytes: Long,
    public val safetyMarginBytes: Long,
) {
    public val requiredBytes: Long = sourceBytes + canonicalTextBytes + coverBytes + diagnosticsBytes + safetyMarginBytes
}

public data class EpubImportPreview(
    public val stagedSource: StagedEpubSource,
    public val document: EpubDocument,
    public val canonical: EpubCanonicalTextPreview,
    public val storage: EpubStorageEstimate,
)

public sealed interface EpubPreviewResult {
    public data class Ready(public val preview: EpubImportPreview) : EpubPreviewResult

    public data class Duplicate(public val existingProject: ExistingEpubProject) : EpubPreviewResult

    public data class Failed(
        public val error: EpubImportError,
        public val message: String,
        public val securityDiagnostic: EpubSecurityDiagnostic? = null,
    ) : EpubPreviewResult
}

public sealed interface EpubAcceptanceResult {
    public data class Published(
        public val source: ImportedEpubSource,
        public val preview: EpubImportPreview,
    ) : EpubAcceptanceResult

    public data class Failed(public val error: EpubImportError, public val message: String) : EpubAcceptanceResult
}

/** Coordinates the uncommitted preview with the existing private source and canonical stores. */
public class EpubImportPreviewService(
    private val sourceRepository: EpubSourceRepository,
    private val parser: EpubDocumentParser,
    private val canonicalText: EpubCanonicalTextService,
) {
    public fun previewSelected(uri: Uri): EpubPreviewResult = previewSource(uri.toString())

    /** Stages, validates, and parses without publishing a source, Room row, Markdown, or warnings. */
    public fun previewSource(sourceUri: String): EpubPreviewResult {
        return when (val staged = sourceRepository.stageSource(sourceUri)) {
            is EpubStageResult.Duplicate -> EpubPreviewResult.Duplicate(staged.existingProject)
            is EpubStageResult.Failed -> EpubPreviewResult.Failed(
                error = staged.error,
                message = staged.error.displayMessage(),
                securityDiagnostic = staged.securityDiagnostic,
            )
            is EpubStageResult.Staged -> previewStaged(staged.source)
        }
    }

    public fun accept(preview: EpubImportPreview): EpubAcceptanceResult {
        val published = sourceRepository.publishStaged(preview.stagedSource)
        if (published !is EpubImportResult.Imported) {
            val failure = published as EpubImportResult.Failed
            return EpubAcceptanceResult.Failed(failure.error, failure.error.displayMessage())
        }
        return when (canonicalText.renderAndPersist(preview.document)) {
            is EpubCanonicalTextResult.Published -> EpubAcceptanceResult.Published(published.source, preview)
            is EpubCanonicalTextResult.Failed -> EpubAcceptanceResult.Failed(
                EpubImportError.PUBLICATION_FAILED,
                "Canonical chapter text could not be published.",
            )
        }
    }

    public fun discard(preview: EpubImportPreview) {
        sourceRepository.discardStaged(preview.stagedSource)
    }

    private fun previewStaged(source: StagedEpubSource): EpubPreviewResult {
        return when (val parsed = parser.parse(source)) {
            is EpubParseResult.Parsed -> {
                val canonical = try {
                    canonicalText.preview(parsed.document)
                } catch (_: Exception) {
                    sourceRepository.discardStaged(source)
                    return EpubPreviewResult.Failed(
                        EpubImportError.PUBLICATION_FAILED,
                        "The EPUB preview could not be rendered.",
                    )
                }
                EpubPreviewResult.Ready(
                    EpubImportPreview(
                        stagedSource = source,
                        document = parsed.document,
                        canonical = canonical,
                        storage = storageEstimate(source, parsed.document, canonical),
                    ),
                )
            }
            is EpubParseResult.Rejected -> {
                sourceRepository.discardStaged(source)
                EpubPreviewResult.Failed(
                    EpubImportError.SECURITY_VALIDATION_FAILED,
                    "The EPUB failed its security validation.",
                    parsed.diagnostic,
                )
            }
            is EpubParseResult.Failed -> {
                sourceRepository.discardStaged(source)
                EpubPreviewResult.Failed(
                    EpubImportError.COPY_FAILED,
                    "The EPUB could not be parsed safely.",
                )
            }
        }
    }

    private fun storageEstimate(
        source: StagedEpubSource,
        document: EpubDocument,
        canonical: EpubCanonicalTextPreview,
    ): EpubStorageEstimate {
        val sourceBytes = source.sizeBytes
        val canonicalBytes = canonical.chapters.sumOf { it.sizeBytes }
        val coverBytes = document.cover?.bytes?.size?.toLong() ?: 0L
        val diagnosticsBytes = canonical.warningReportSizeBytes
        val durableBytes = sourceBytes + canonicalBytes + coverBytes + diagnosticsBytes
        return EpubStorageEstimate(
            sourceBytes = sourceBytes,
            canonicalTextBytes = canonicalBytes,
            coverBytes = coverBytes,
            diagnosticsBytes = diagnosticsBytes,
            safetyMarginBytes = maxOf(64 * 1024L, durableBytes / 10),
        )
    }

    private fun EpubImportError.displayMessage(): String = when (this) {
        EpubImportError.SOURCE_UNAVAILABLE -> "The selected EPUB is unavailable."
        EpubImportError.COPY_FAILED -> "The selected EPUB could not be copied privately."
        EpubImportError.PUBLICATION_FAILED -> "The EPUB could not be published."
        EpubImportError.INDEX_LOOKUP_FAILED -> "The existing book index could not be checked."
        EpubImportError.INDEX_WRITE_FAILED -> "The imported book could not be recorded."
        EpubImportError.SECURITY_VALIDATION_FAILED -> "The EPUB failed its security validation."
    }
}
